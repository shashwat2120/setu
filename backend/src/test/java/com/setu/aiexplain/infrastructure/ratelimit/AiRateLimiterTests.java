package com.setu.aiexplain.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 14 §17/§19: real Redis (the {@code setu-redis-1} container, not a
 * mock — same discipline as every PostgreSQL-backed test in this
 * codebase), configured with the app's real default window (60s/20
 * requests) — see application.yml. Uses a fresh random (tenant, user)
 * pair per test so tests never share a rate-limit bucket.
 */
@SpringBootTest
class AiRateLimiterTests {

    @Autowired
    AiRateLimiter rateLimiter;

    @Test
    void allowsUpToTheConfiguredLimitThenDenies() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        int allowed = 0;
        for (int i = 0; i < 25; i++) {
            if (rateLimiter.tryAcquire(tenantId, userId)) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(20); // app.ai.rate-limit.max-requests default
    }

    @Test
    void differentUsersInTheSameTenantHaveIndependentBudgets() {
        UUID tenantId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            assertThat(rateLimiter.tryAcquire(tenantId, userA)).isTrue();
        }
        assertThat(rateLimiter.tryAcquire(tenantId, userA)).isFalse();
        // userB's own budget is untouched by userA's exhaustion.
        assertThat(rateLimiter.tryAcquire(tenantId, userB)).isTrue();
    }

    /** Concurrency: many threads racing to increment the SAME (tenant, user) key must never admit more than the configured limit — Redis's own atomic INCR is what guarantees this, not application-level locking. */
    @Test
    void concurrentAcquisitions_neverExceedTheLimit() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        int threadCount = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                tasks.add(() -> {
                    ready.countDown();
                    start.await();
                    return rateLimiter.tryAcquire(tenantId, userId);
                });
            }
            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (Callable<Boolean> t : tasks) {
                futures.add(pool.submit(t));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            long successCount = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(15, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }
            assertThat(successCount).isEqualTo(20);
        } finally {
            pool.shutdown();
        }
    }
}
