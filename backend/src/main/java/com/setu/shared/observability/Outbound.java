package com.setu.shared.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * One call this service makes to another over a client nothing instruments for us (the gateway's upstream calls, the AI service's facts, the
 * guardian-grant check). It does two things around the call: it opens an observation, which is a span in the trace and a timer
 * ({@code setu.outbound}, tagged with the peer and the operation, never with an id or a path parameter) in the metrics; and it hands the call the
 * {@code traceparent} to send, so the other service's spans join this trace instead of starting their own.
 * <p>
 * With tracing off the call simply runs with no header. A failure is recorded on the span and rethrown unchanged: observing a call never
 * changes what it does.
 */
public class Outbound {

    /** For code that has no observability wired (plain unit tests, a client built by hand): runs the call, sends no header. */
    public static final Outbound NONE = new Outbound(null, null);

    /** The call to make. {@code traceParent} is the header value to send, or null. */
    @FunctionalInterface
    public interface Action<T, E extends Exception> {
        T run(String traceParent) throws E;
    }

    private final ObservationRegistry registry;
    private final TraceParents traceParents;

    public Outbound(ObservationRegistry registry, TraceParents traceParents) {
        this.registry = registry;
        this.traceParents = traceParents;
    }

    public <T, E extends Exception> T call(String peer, String operation, Action<T, E> action) throws E {
        if (registry == null || registry.isNoop()) {
            return action.run(null);
        }
        Observation observation = Observation.createNotStarted("setu.outbound", registry)
            .lowCardinalityKeyValue("peer", peer)
            .lowCardinalityKeyValue("operation", operation)
            .start();
        try (Observation.Scope ignored = observation.openScope()) {
            return action.run(traceParents == null ? null : traceParents.current().orElse(null));
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
