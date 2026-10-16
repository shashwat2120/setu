package com.setu.shared.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * One synchronous call to another service, bounded in every way that matters: a circuit breaker that stops asking a service that keeps
 * failing, and a bulkhead that caps how many request threads may be waiting on it at once, so a slow dependency cannot take this service's
 * own threads with it. The call's own timeout belongs to the client that makes it.
 * <p>
 * It deliberately has <b>no fallback</b>. When the call cannot be made or did not finish, the caller gets {@link Unavailable} and must decide
 * honestly what that means for its own purpose; nothing here ever invents an answer. (docs/contracts/data-ownership.md, "Synchronous calls".)
 */
public final class GuardedCall {

    /** The dependency could not be asked or did not answer: the circuit is open, the bulkhead is full, or the call failed. */
    public static final class Unavailable extends Exception {
        private final String reason;

        public Unavailable(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        /** {@code circuit_open}, {@code bulkhead_full} or {@code failed}: a metric tag, not free text. */
        public String reason() {
            return reason;
        }
    }

    public record Settings(int failureRateThreshold, int minimumCalls, int slidingWindowSize, Duration openFor, int maxConcurrent,
                           Duration maxWaitForPermit) {

        /** Opens at 50% failures over at least 10 of the last 20 calls, stays open 10 s, allows 20 callers at once and does not queue. */
        public static Settings defaults() {
            return new Settings(50, 10, 20, Duration.ofSeconds(10), 20, Duration.ZERO);
        }
    }

    private final String name;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final MeterRegistry meters;

    public GuardedCall(String name, Settings settings, Predicate<Throwable> countsAsFailure, MeterRegistry meters) {
        this.name = name;
        this.meters = meters;
        this.breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
            .failureRateThreshold(settings.failureRateThreshold())
            .minimumNumberOfCalls(settings.minimumCalls())
            .slidingWindowSize(settings.slidingWindowSize())
            .waitDurationInOpenState(settings.openFor())
            .permittedNumberOfCallsInHalfOpenState(3)
            .recordException(countsAsFailure)
            .build());
        this.bulkhead = Bulkhead.of(name, BulkheadConfig.custom()
            .maxConcurrentCalls(settings.maxConcurrent())
            .maxWaitDuration(settings.maxWaitForPermit())
            .build());
    }

    public <T> T call(Callable<T> call) throws Unavailable {
        try {
            return Bulkhead.decorateCallable(bulkhead, CircuitBreaker.decorateCallable(breaker, call)).call();
        } catch (CallNotPermittedException e) {
            throw unavailable("circuit_open", e);
        } catch (BulkheadFullException e) {
            throw unavailable("bulkhead_full", e);
        } catch (Exception e) {
            throw unavailable("failed", e);
        }
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    private Unavailable unavailable(String reason, Throwable cause) {
        if (meters != null) {
            meters.counter("setu.sync_call.unavailable", "call", name, "reason", reason).increment();
        }
        return new Unavailable(reason, cause);
    }
}
