package com.setu.shared.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guarantees of a synchronous call to another service (docs/contracts/data-ownership.md): it stops asking a service that keeps failing,
 * it never lets more than a fixed number of threads wait on one dependency, and it never invents an answer: every failure surfaces as
 * {@link GuardedCall.Unavailable} with a reason the caller and the metrics can tell apart.
 */
class GuardedCallTests {

    private static GuardedCall.Settings settings(int maxConcurrent) {
        return new GuardedCall.Settings(50, 4, 4, Duration.ofSeconds(30), maxConcurrent, Duration.ZERO);
    }

    @Test
    void aSuccessfulCallReturnsItsResult() throws Exception {
        GuardedCall call = new GuardedCall("t", settings(5), e -> true, new SimpleMeterRegistry());
        assertThat(call.call(() -> "answer")).isEqualTo("answer");
        assertThat(call.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailureIsUnavailableNotAnInventedAnswer() {
        var meters = new SimpleMeterRegistry();
        GuardedCall call = new GuardedCall("t", settings(5), e -> true, meters);

        assertThatThrownBy(() -> call.call(() -> {
            throw new IOException("connection refused");
        })).isInstanceOfSatisfying(GuardedCall.Unavailable.class, e -> assertThat(e.reason()).isEqualTo("failed"))
            .hasRootCauseMessage("connection refused");
        assertThat(meters.counter("setu.sync_call.unavailable", "call", "t", "reason", "failed").count()).isEqualTo(1.0);
    }

    @Test
    void aDependencyThatKeepsFailingIsNotAskedAgainUntilItHasHadTimeToRecover() {
        var meters = new SimpleMeterRegistry();
        GuardedCall call = new GuardedCall("t", settings(5), e -> true, meters);
        AtomicInteger asked = new AtomicInteger();

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> call.call(() -> {
                asked.incrementAndGet();
                throw new IOException("down");
            })).isInstanceOf(GuardedCall.Unavailable.class);
        }
        assertThat(call.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> call.call(() -> {
            asked.incrementAndGet();
            return "never";
        })).isInstanceOfSatisfying(GuardedCall.Unavailable.class, e -> assertThat(e.reason()).isEqualTo("circuit_open"));
        assertThat(asked.get()).as("the open circuit did not call the dependency").isEqualTo(4);
        assertThat(meters.counter("setu.sync_call.unavailable", "call", "t", "reason", "circuit_open").count()).isEqualTo(1.0);
    }

    @Test
    void onlyAsManyCallersAsTheBulkheadAllowsWaitOnTheDependency() throws Exception {
        GuardedCall call = new GuardedCall("t", settings(2), e -> true, new SimpleMeterRegistry());
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                Future<String> slow = pool.submit(() -> call.call(() -> {
                    inside.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return "slow";
                }));
                assertThat(slow).isNotNull();
            }
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> call.call(() -> "third")).isInstanceOfSatisfying(GuardedCall.Unavailable.class,
                e -> assertThat(e.reason()).as("a third caller is turned away at once instead of queueing").isEqualTo("bulkhead_full"));
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }

    @Test
    void failuresTheCallerDoesNotCountDoNotOpenTheCircuit() {
        GuardedCall call = new GuardedCall("t", settings(5), e -> !(e instanceof IllegalArgumentException), new SimpleMeterRegistry());
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> call.call(() -> {
                throw new IllegalArgumentException("a bad request is not a sick dependency");
            })).isInstanceOf(GuardedCall.Unavailable.class);
        }
        assertThat(call.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
