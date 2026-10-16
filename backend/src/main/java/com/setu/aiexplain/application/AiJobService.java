package com.setu.aiexplain.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.aifacts.AiFacts;
import com.setu.shared.authorization.AuthenticatedUser;
import com.setu.shared.websocket.LiveBroadcastRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * An AI question that is answered in the background (canonical spec section 14: queued / running / completed). {@code POST /ai/jobs} accepts
 * the question and answers at once with a job id; the same single bounded model call as {@code /ai/chat} then runs on a worker, and the job
 * moves {@code QUEUED -> RUNNING -> COMPLETED | FAILED}. Every change of status is pushed to the owner's own topic
 * {@code /topic/ai/jobs/<userId>} (status only, no answer text) and the result is read with {@code GET /ai/jobs/<id>}.
 * <p>
 * <b>What a job is allowed to be.</b> It carries the identity of whoever asked, captured when the job was accepted, and runs the chat <i>as
 * that person</i>: the facts it fetches are authorized for them, with their own token, exactly as for a synchronous question. A job record is
 * visible to its owner only; anyone else asking for its id is told it does not exist. Records live in Redis with a time limit (nothing here
 * needs a database, and an answer is not kept for ever), so a restart loses queued jobs, which then read as not found rather than as an
 * answer that never comes: the client resubmits.
 * <p>
 * <b>Bounded.</b> A fixed number of workers and a bounded queue; a full queue is refused at submission (503), never accepted and silently
 * dropped. The per-user rate limit applies when the job runs, because it is the same model call.
 */
@Service
public class AiJobService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AiJobService.class);
    private static final String KEY_PREFIX = "setu:ai:job:";

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    /** One job as its owner sees it. {@code answer}, {@code sources} and {@code model} are set when COMPLETED, {@code failure} when FAILED. */
    public record Job(UUID id, UUID userId, UUID tenantId, Status status, Instant createdAt, Instant updatedAt, String answer,
                      List<String> sources, String model, String failure) {

        Job with(Status next, String answer, List<String> sources, String model, String failure) {
            return new Job(id, userId, tenantId, next, createdAt, Instant.now(), answer, sources, model, failure);
        }
    }

    public sealed interface Submission {
        record Accepted(Job job) implements Submission {
        }

        record Invalid(String reason) implements Submission {
        }

        /** The queue is full or the caller could not be identified: nothing was accepted. */
        record Unavailable(String reason) implements Submission {
        }
    }

    private final AiChatService chat;
    private final AiFacts facts;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final Duration retention;
    private final ThreadPoolExecutor workers;

    public AiJobService(AiChatService chat, AiFacts facts, StringRedisTemplate redis, ObjectMapper objectMapper, ApplicationEventPublisher events,
                        @Value("${app.ai.jobs.workers:4}") int workers,
                        @Value("${app.ai.jobs.queue-capacity:50}") int queueCapacity,
                        @Value("${app.ai.jobs.retention-minutes:60}") long retentionMinutes) {
        this.chat = chat;
        this.facts = facts;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.events = events;
        this.retention = Duration.ofMinutes(retentionMinutes);
        this.workers = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
            runnable -> {
                Thread thread = new Thread(runnable, "ai-job");
                thread.setDaemon(true);
                return thread;
            });
    }

    public static final String TOPIC_PREFIX = "/topic/ai/jobs/";

    /** The topic a user's job status changes are pushed to. */
    public static String topicFor(UUID userId) {
        return TOPIC_PREFIX + userId;
    }

    public Submission submit(String message) {
        Optional<String> invalid = chat.validate(message);
        if (invalid.isPresent()) {
            return new Submission.Invalid(invalid.get());
        }
        AiFacts.Caller caller;
        try {
            caller = facts.caller();
        } catch (AiFacts.Unavailable e) {
            return new Submission.Unavailable("the AI service cannot identify you right now");
        }
        Instant now = Instant.now();
        Job job = new Job(UUID.randomUUID(), caller.userId(), caller.tenantId(), Status.QUEUED, now, now, null, null, null, null);
        SecurityContext callerContext = SecurityContextHolder.getContext();
        save(job);
        publish(job);
        try {
            workers.execute(() -> run(job, message, callerContext));
        } catch (RejectedExecutionException e) {
            // announced as queued a moment ago, so say what became of it before forgetting it
            publish(job.with(Status.FAILED, null, null, null, "queue_full"));
            delete(job.id());
            return new Submission.Unavailable("too many AI questions are waiting; try again shortly");
        }
        recordAudit("AI_JOB_SUBMITTED", job, Map.of("messageLength", message.length()));
        return new Submission.Accepted(job);
    }

    /** The job, if it exists and belongs to the person asking. Anything else, including someone else's job, is empty. */
    public Optional<Job> find(UUID jobId) {
        UUID me = currentUserId();
        return load(jobId).filter(job -> job.userId().equals(me));
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.userId();
        }
        return facts.caller().userId();
    }

    private void run(Job queued, String message, SecurityContext callerContext) {
        SecurityContextHolder.setContext(callerContext);
        Job running = queued.with(Status.RUNNING, null, null, null, null);
        try {
            save(running);
            publish(running);
            AiChatService.ChatResult result = chat.chat(message);
            Job done = switch (result) {
                case AiChatService.ChatResult.Success s -> running.with(Status.COMPLETED, s.answer(), s.sources(), s.model(), null);
                case AiChatService.ChatResult.InvalidRequest r -> running.with(Status.FAILED, null, null, null, "invalid_request");
                case AiChatService.ChatResult.RateLimited r -> running.with(Status.FAILED, null, null, null, "rate_limited");
                case AiChatService.ChatResult.ProviderUnavailable r -> running.with(Status.FAILED, null, null, null, "unavailable");
            };
            save(done);
            publish(done);
            recordAudit(done.status() == Status.COMPLETED ? "AI_JOB_COMPLETED" : "AI_JOB_FAILED", done,
                Map.of("status", done.status().name()));
        } catch (RuntimeException e) {
            log.warn("AI job {} failed", queued.id(), e);
            Job failed = running.with(Status.FAILED, null, null, null, "internal_error");
            try {
                save(failed);
                publish(failed);
            } catch (RuntimeException secondary) {
                log.warn("AI job {} failure could not be recorded", queued.id(), secondary);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void recordAudit(String action, Job job, Map<String, Object> details) {
        try {
            facts.audit(action, job.id(), details);
        } catch (RuntimeException e) {
            log.warn("AI audit record {} for job {} could not be written: {}", action, job.id(), e.getMessage());
        }
    }

    private void publish(Job job) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("jobId", job.id().toString());
        event.put("status", job.status().name());
        event.put("at", job.updatedAt().toString());
        events.publishEvent(new LiveBroadcastRequest(topicFor(job.userId()), event));
    }

    private void save(Job job) {
        try {
            redis.opsForValue().set(KEY_PREFIX + job.id(), objectMapper.writeValueAsString(job), retention);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<Job> load(UUID jobId) {
        String json = redis.opsForValue().get(KEY_PREFIX + jobId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, Job.class));
        } catch (JsonProcessingException e) {
            log.warn("AI job {} record is unreadable", jobId, e);
            return Optional.empty();
        }
    }

    private void delete(UUID jobId) {
        redis.delete(KEY_PREFIX + jobId);
    }

    @Override
    public void destroy() {
        workers.shutdownNow();
    }
}
