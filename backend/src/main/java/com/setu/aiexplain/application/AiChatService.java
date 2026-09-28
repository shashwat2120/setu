package com.setu.aiexplain.application;

import com.setu.aiexplain.infrastructure.ratelimit.AiRateLimiter;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Day 14 §AI: the orchestration layer — see the architecture flow in the
 * canonical spec §10. Nothing here is asynchronous/queued: a single
 * {@code POST /ai/chat} call runs authentication (already done by
 * SecurityConfig before this class ever runs) -&gt; tenant resolution -&gt;
 * rate limit -&gt; a single bounded model call (with tool-calling handled
 * transparently by {@link ChatClient}) -&gt; audit -&gt; response. The
 * queued/running/completed async job shape the canonical spec's demo
 * targets (§14) mention is Sprint 6B's AI-Service-extraction scope
 * (AGENTS.md 6B.3), not Day 14's — disclosed here rather than silently
 * assumed.
 * <p>
 * No conversation memory of any kind is kept (Day 14 §15 explicit
 * instruction) — every call is one independent turn; nothing about a
 * prior message influences a later one.
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final ChatClient chatClient;
    private final ChatClient fallbackChatClient;
    private final AiToolService aiToolService;
    private final AiToolInvocationRecorder invocationRecorder;
    private final AiRateLimiter rateLimiter;
    private final TenantContext tenantContext;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final String modelName;
    private final String fallbackModelName;
    private final boolean fallbackIsRedundant;
    private final long timeoutSeconds;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Day 16 §ai-perf: {@code chatClient} is whichever provider {@code spring.ai.model.chat}
     * selected as primary (Gemini by default — see AiChatConfig/application.yml);
     * {@code fallbackChatClient} is ALWAYS the Ollama-backed client (see
     * AiChatConfig#fallbackChatClient), regardless of which is primary. If the primary
     * provider is itself Ollama, {@code fallbackIsRedundant} skips the pointless
     * retry-through-the-same-provider rather than doubling an already-slow local call.
     */
    public AiChatService(ChatClient chatClient, @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                          AiToolService aiToolService, AiToolInvocationRecorder invocationRecorder,
                          AiRateLimiter rateLimiter, TenantContext tenantContext, AuthorizationContext authorizationContext,
                          AuditLogWriter auditLogWriter,
                          @Value("${spring.ai.model.chat:google-genai}") String activeProvider,
                          @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}") String geminiModel,
                          @Value("${spring.ai.ollama.chat.options.model:llama3.2:3b}") String ollamaModel,
                          @Value("${app.ai.request-timeout-seconds:30}") long timeoutSeconds) {
        this.chatClient = chatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.aiToolService = aiToolService;
        this.invocationRecorder = invocationRecorder;
        this.rateLimiter = rateLimiter;
        this.tenantContext = tenantContext;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.modelName = "ollama".equals(activeProvider) ? ollamaModel : geminiModel;
        this.fallbackModelName = ollamaModel;
        this.fallbackIsRedundant = "ollama".equals(activeProvider);
        this.timeoutSeconds = timeoutSeconds;
    }

    public sealed interface ChatResult {
        record Success(String answer, List<String> sources, Instant generatedAt, String model, UUID correlationId) implements ChatResult {
        }

        record InvalidRequest(String reason) implements ChatResult {
        }

        record RateLimited() implements ChatResult {
        }

        record ProviderUnavailable() implements ChatResult {
        }
    }

    public ChatResult chat(String message) {
        if (message == null || message.isBlank()) {
            return new ChatResult.InvalidRequest("message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return new ChatResult.InvalidRequest("message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }

        UUID userId = authorizationContext.currentUserId();
        Optional<UUID> tenantId = tenantContext.tenantId();
        UUID correlationId = UUID.randomUUID();

        if (tenantId.isPresent() && !rateLimiter.tryAcquire(tenantId.get(), userId)) {
            auditLogWriter.write(tenantId.get(), userId, "AI_CHAT_RATE_LIMITED", "ai_chat", correlationId, Map.of());
            return new ChatResult.RateLimited();
        }

        invocationRecorder.drain(); // clear any stale state left on this (pooled) thread by an earlier call
        try {
            return attempt(chatClient, message, modelName, tenantId, userId, correlationId);
        } catch (Exception primaryFailure) {
            logProviderFailure(primaryFailure, tenantId, userId, correlationId, "primary");
            if (fallbackIsRedundant) {
                return new ChatResult.ProviderUnavailable();
            }
            invocationRecorder.drain();
            try {
                ChatResult result = attempt(fallbackChatClient, message, fallbackModelName, tenantId, userId, correlationId);
                auditLogWriter.write(tenantId.orElse(null), userId, "AI_CHAT_FALLBACK_USED", "ai_chat", correlationId,
                    Map.of("fallbackModel", fallbackModelName));
                return result;
            } catch (Exception fallbackFailure) {
                logProviderFailure(fallbackFailure, tenantId, userId, correlationId, "fallback");
                return new ChatResult.ProviderUnavailable();
            }
        }
    }

    /**
     * Runs one provider attempt end to end (call, tool sources, success audit); throws on timeout or any
     * provider-side failure for the caller to handle (retry via fallback, or give up).
     * <p>
     * Real bug found live: {@code SecurityContextHolder}'s default strategy is thread-local, and
     * {@link #executor} runs the call (and therefore every tool invocation {@code .tools(aiToolService)} makes
     * synchronously inside it) on a brand-new virtual thread. With no propagation, every tool's "resolve the
     * current principal" path (self-attendance, self-grades, self-deadlines — the primary way this advisor is
     * meant to be used, per each tool's own "omit X for the caller's own record" description) saw an empty
     * security context and reported "not found" regardless of which model answered. Capturing the context on
     * the calling thread and restoring it inside the submitted task is the standard fix for this exact
     * ThreadLocal-across-executor-boundary gap.
     */
    private ChatResult attempt(ChatClient client, String message, String modelForResult,
                                Optional<UUID> tenantId, UUID userId, UUID correlationId) throws Exception {
        SecurityContext callerContext = SecurityContextHolder.getContext();
        String answer = executor.submit(() -> {
                SecurityContextHolder.setContext(callerContext);
                try {
                    return client.prompt()
                        .user(message)
                        .tools(aiToolService)
                        .call()
                        .content();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            })
            .get(timeoutSeconds, TimeUnit.SECONDS);
        List<String> sources = invocationRecorder.drain();
        auditLogWriter.write(tenantId.orElse(null), userId, "AI_CHAT_COMPLETED", "ai_chat", correlationId,
            Map.of("toolsInvoked", sources, "messageLength", message.length(), "model", modelForResult));
        return new ChatResult.Success(answer, sources, Instant.now(), modelForResult, correlationId);
    }

    /** Never leaks a stack trace or connection details to the caller (no response body reveals this) — logged server-side only, per this class's existing error-handling contract. */
    private void logProviderFailure(Exception e, Optional<UUID> tenantId, UUID userId, UUID correlationId, String which) {
        invocationRecorder.drain();
        if (e instanceof TimeoutException) {
            auditLogWriter.write(tenantId.orElse(null), userId, "AI_CHAT_TIMEOUT", "ai_chat", correlationId, Map.of("provider", which));
        } else {
            log.warn("AI chat {} provider call failed", which, e);
            auditLogWriter.write(tenantId.orElse(null), userId, "AI_CHAT_PROVIDER_ERROR", "ai_chat", correlationId,
                Map.of("errorType", e.getClass().getSimpleName(), "provider", which));
        }
    }
}
