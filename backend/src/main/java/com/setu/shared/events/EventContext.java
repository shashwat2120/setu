package com.setu.shared.events;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the ids that tie events together come from, so a business flow can be followed from the request that started it through every event
 * it causes, across services.
 * <ul>
 *   <li><b>Correlation id</b> names the whole flow. For an event written while serving an HTTP request it is that request's
 *       {@code X-Correlation-Id} (the gateway forwards it, every service's filter puts it in the logging context), so the event, the request's
 *       log lines and the response header all carry one id. For an event written while a consumer is processing another event it is that
 *       event's correlation id, so a chain of events stays one flow.</li>
 *   <li><b>Causation id</b> names the direct cause: the id of the event a consumer was processing when it wrote this one. A root event has none.</li>
 * </ul>
 * A consumer opens a context around handling one event; {@code OutboxEventWriter} reads it. Without a context the writer falls back to the
 * request's correlation id, and failing that to what the event itself carries.
 */
public final class EventContext {

    private record Ids(UUID correlationId, UUID causationId) {
    }

    private static final ThreadLocal<Ids> CURRENT = new ThreadLocal<>();

    private EventContext() {
    }

    /** Closes the context opened by {@link #open}, restoring whatever was there before. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Marks the current thread as handling an event: events written from now until the scope is closed continue its flow. Also puts the
     * correlation id in the logging context so the consumer's log lines carry it.
     *
     * @param correlationId the handled event's correlation id (may be null if the event had none)
     * @param causationId   the handled event's own id, which becomes the cause of whatever is written
     */
    public static Scope open(UUID correlationId, UUID causationId) {
        Ids previous = CURRENT.get();
        String previousMdc = MDC.get("correlationId");
        CURRENT.set(new Ids(correlationId, causationId));
        if (correlationId != null) {
            MDC.put("correlationId", correlationId.toString());
        }
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (previousMdc == null) {
                MDC.remove("correlationId");
            } else {
                MDC.put("correlationId", previousMdc);
            }
        };
    }

    /** The correlation id events written now should carry, if the context or the current request names one. */
    public static Optional<UUID> correlationId() {
        Ids ids = CURRENT.get();
        if (ids != null && ids.correlationId() != null) {
            return Optional.of(ids.correlationId());
        }
        return Optional.ofNullable(MDC.get("correlationId")).filter(s -> !s.isBlank()).map(EventContext::fromRequestId);
    }

    /** The id of the event being handled, which is the cause of whatever is written now; empty outside a consumer. */
    public static Optional<UUID> causationId() {
        Ids ids = CURRENT.get();
        return ids == null ? Optional.empty() : Optional.ofNullable(ids.causationId());
    }

    /**
     * A request's correlation id is any short token; an event's is a UUID. A token that is already a UUID is used as it is; any other maps to
     * the same UUID every time, so the request header and the event always agree.
     */
    public static UUID fromRequestId(String requestId) {
        try {
            return UUID.fromString(requestId);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(("setu-request:" + requestId).getBytes(StandardCharsets.UTF_8));
        }
    }
}
