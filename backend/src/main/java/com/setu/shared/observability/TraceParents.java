package com.setu.shared.observability;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The W3C {@code traceparent} of whatever span is current on this thread, as a header value ({@code 00-<trace id>-<span id>-<flags>}). It is how
 * a trace crosses the places where nothing instruments the hop for us: a request this service makes with a plain HTTP client, and an event
 * written to the outbox now and published to Kafka later by another thread (docs/contracts/observability.md).
 * <p>
 * Empty when tracing is off or no span is current; callers then simply send nothing. A value read from outside (a stored column, a header) is
 * only ever passed on if it is well formed ({@link #isWellFormed}), so a malformed or hostile string is dropped, not forwarded.
 */
@Component
public class TraceParents {

    private static final Pattern FORMAT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final String NO_TRACE = "00000000000000000000000000000000";

    private final ObjectProvider<Tracer> tracer;

    public TraceParents(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public Optional<String> current() {
        Tracer available = tracer.getIfAvailable();
        if (available == null || available.currentSpan() == null) {
            return Optional.empty();
        }
        return format(available.currentSpan().context());
    }

    static Optional<String> format(TraceContext context) {
        if (context == null || context.traceId() == null || context.spanId() == null) {
            return Optional.empty();
        }
        String traceId = pad(context.traceId(), 32);
        String spanId = pad(context.spanId(), 16);
        if (NO_TRACE.equals(traceId)) {
            return Optional.empty();
        }
        String value = "00-" + traceId + "-" + spanId + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
        return isWellFormed(value) ? Optional.of(value) : Optional.empty();
    }

    public static boolean isWellFormed(String traceParent) {
        return traceParent != null && FORMAT.matcher(traceParent).matches();
    }

    private static String pad(String hex, int length) {
        String lower = hex.toLowerCase();
        return lower.length() >= length ? lower : "0".repeat(length - lower.length()) + lower;
    }
}
