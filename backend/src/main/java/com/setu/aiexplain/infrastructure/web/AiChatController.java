package com.setu.aiexplain.infrastructure.web;

import com.setu.aiexplain.application.AiChatService;
import com.setu.aiexplain.application.AiJobService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Day 14 §20: the AI chat API. The request is intent, not an
 * implementation command — {@code message} is the user's own literal
 * question; nothing else about the request (no tenant id, no repository
 * name, no tool name, no SQL, no earlier conversation turns) is ever
 * accepted from the client. Conversation memory is held server-side.
 */
@RestController
@RequestMapping("/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final AiChatService aiChatService;
    private final AiJobService aiJobService;
    private final MeterRegistry meterRegistry;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AiChatController(AiChatService aiChatService, AiJobService aiJobService, MeterRegistry meterRegistry) {
        this.aiChatService = aiChatService;
        this.aiJobService = aiJobService;
        this.meterRegistry = meterRegistry;
    }

    /** Counts each request by outcome and answering model (never by user or question), so a provider outage or a rate-limit storm shows up. */
    private void countRequest(AiChatService.ChatResult result, String channel) {
        String outcome = switch (result) {
            case AiChatService.ChatResult.Success r -> "answered";
            case AiChatService.ChatResult.InvalidRequest r -> "invalid";
            case AiChatService.ChatResult.RateLimited r -> "rate_limited";
            case AiChatService.ChatResult.ProviderUnavailable r -> "unavailable";
        };
        String model = result instanceof AiChatService.ChatResult.Success r && r.model() != null ? r.model() : "none";
        meterRegistry.counter("setu.ai.chat.requests", "outcome", outcome, "model", model, "channel", channel).increment();
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(String answer, List<String> sources, Instant generatedAt, String model, UUID correlationId) {
    }

    public record ChatErrorResponse(String reason) {
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        var result = aiChatService.chat(request == null ? null : request.message());
        countRequest(result, "chat");
        return switch (result) {
            case AiChatService.ChatResult.Success r ->
                ResponseEntity.ok(new ChatResponse(r.answer(), r.sources(), r.generatedAt(), r.model(), r.correlationId()));
            case AiChatService.ChatResult.InvalidRequest r ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ChatErrorResponse(r.reason()));
            case AiChatService.ChatResult.RateLimited ignored ->
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ChatErrorResponse("AI request rate limit exceeded, try again shortly"));
            case AiChatService.ChatResult.ProviderUnavailable ignored ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ChatErrorResponse("AI service is currently unavailable"));
        };
    }

    /**
     * Same contract as {@link #chat} delivered as server-sent events: zero or more {@code tool} events (the raw tool
     * name, so the UI can say "Checking your attendance…"), {@code token} events (answer text pieces), then exactly one
     * {@code done} (same fields as {@link ChatResponse}) or {@code error} event. A request that is invalid is rejected
     * with a plain 400 before any stream starts, exactly like {@link #chat}.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object chatStream(@RequestBody ChatRequest request) {
        String message = request == null ? null : request.message();
        var invalid = aiChatService.validate(message);
        if (invalid.isPresent()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(new ChatErrorResponse(invalid.get()));
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SecurityContext callerContext = SecurityContextHolder.getContext();
        streamExecutor.execute(() -> {
            // the principal (and through it the tenant) is thread-local — restore it on this worker thread
            SecurityContextHolder.setContext(callerContext);
            try {
                var result = aiChatService.chat(message, new AiChatService.StreamListener() {
                    @Override
                    public void onTool(String toolName) {
                        send(emitter, "tool", Map.of("name", toolName));
                    }

                    @Override
                    public void onToken(String text) {
                        send(emitter, "token", Map.of("text", text));
                    }
                });
                countRequest(result, "stream");
                switch (result) {
                    case AiChatService.ChatResult.Success r -> send(emitter, "done",
                        new ChatResponse(r.answer(), r.sources(), r.generatedAt(), r.model(), r.correlationId()));
                    case AiChatService.ChatResult.InvalidRequest r -> send(emitter, "error",
                        Map.of("status", 400, "reason", r.reason()));
                    case AiChatService.ChatResult.RateLimited ignored -> send(emitter, "error",
                        Map.of("status", 429, "reason", "AI request rate limit exceeded, try again shortly"));
                    case AiChatService.ChatResult.ProviderUnavailable ignored -> send(emitter, "error",
                        Map.of("status", 503, "reason", "AI service is currently unavailable"));
                }
                emitter.complete();
            } catch (Exception e) {
                log.warn("AI chat stream failed", e);
                send(emitter, "error", Map.of("status", 503, "reason", "AI service is currently unavailable"));
                emitter.complete();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    public record JobAccepted(UUID jobId, String status, String statusUrl, String topic) {
    }

    public record JobView(UUID jobId, String status, Instant createdAt, Instant updatedAt, String answer, List<String> sources, String model,
                          String failure) {
    }

    /**
     * Asks a question to be answered in the background. Answers at once (202) with a job id; progress is pushed to the caller's own
     * {@code /topic/ai/jobs/<userId>} and the result is read from {@code statusUrl}. The same question rules as {@link #chat}: 400 for an
     * invalid one, and 503 when nothing could be accepted (queue full, or the caller could not be identified), never a silent drop.
     */
    @PostMapping("/jobs")
    public ResponseEntity<?> submitJob(@RequestBody ChatRequest request) {
        var submission = aiJobService.submit(request == null ? null : request.message());
        meterRegistry.counter("setu.ai.jobs.submitted", "outcome", switch (submission) {
            case AiJobService.Submission.Accepted a -> "accepted";
            case AiJobService.Submission.Invalid i -> "invalid";
            case AiJobService.Submission.Unavailable u -> "unavailable";
        }).increment();
        return switch (submission) {
            case AiJobService.Submission.Accepted a -> ResponseEntity.accepted().body(new JobAccepted(a.job().id(), a.job().status().name(),
                "/ai/jobs/" + a.job().id(), AiJobService.topicFor(a.job().userId())));
            case AiJobService.Submission.Invalid i -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ChatErrorResponse(i.reason()));
            case AiJobService.Submission.Unavailable u -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ChatErrorResponse(u.reason()));
        };
    }

    /** One of the caller's own jobs. Another person's job, and one that does not exist or has expired, are the same 404. */
    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> job(@PathVariable UUID id) {
        return aiJobService.find(id)
            .<ResponseEntity<?>>map(job -> ResponseEntity.ok(new JobView(job.id(), job.status().name(), job.createdAt(), job.updatedAt(), job.answer(),
                job.sources(), job.model(), job.failure())))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ChatErrorResponse("no such job")));
    }

    /** "New conversation": forgets what this user has said to the advisor so far. */
    @DeleteMapping("/chat/history")
    public ResponseEntity<Void> clearHistory() {
        aiChatService.clearHistory();
        return ResponseEntity.noContent().build();
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // client went away or stream already completed — nothing useful to do
        }
    }
}
