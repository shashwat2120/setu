package com.setu.finance.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 12 §Finance concurrency: real PostgreSQL, real threads — never
 * claims exactly-once semantics beyond what's actually proven. Deliberately
 * NOT {@code @Transactional} — every scenario here needs genuinely separate,
 * concurrently-committing transactions (same reasoning as every other
 * concurrency test class in this codebase).
 */
@SpringBootTest
class FinanceConcurrencyTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    PaymentService paymentService;
    @Autowired
    StudentAccountService studentAccountService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        return new Scenario(tenant, student);
    }

    private PaymentService.InitiateResult initiateAs(UUID userId, UUID studentId, BigDecimal amount, String key) {
        TestAuthentication.authenticateAs(userId);
        try {
            return paymentService.initiatePayment(studentId, amount, "INR", "UPI", key);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** TEST 1: two concurrent, genuinely different payment initiations (distinct idempotency keys) for the SAME student — both must succeed as distinct payment rows; no lost update, no spurious conflict. */
    @Test
    void concurrentDistinctPaymentCreation_bothSucceed() throws Exception {
        var s = setUp("FCR1");

        List<PaymentService.InitiateResult> results = runConcurrently(
            () -> initiateAs(s.student.userId(), s.student.studentId(), new BigDecimal("100"), "key-" + UUID.randomUUID()),
            () -> initiateAs(s.student.userId(), s.student.studentId(), new BigDecimal("200"), "key-" + UUID.randomUUID()));

        assertThat(results).allMatch(r -> r instanceof PaymentService.InitiateResult.Success);
        var ids = results.stream().map(r -> ((PaymentService.InitiateResult.Success) r).paymentId()).distinct().toList();
        assertThat(ids).hasSize(2);
    }

    /** TEST 2: two concurrent requests with the SAME idempotency key and SAME payload — exactly one payment row is ever created; the loser either replays the winner's result or reports still-processing, never creates a second row. */
    @Test
    void concurrentSameKeySamePayload_neverCreatesDuplicateFinancialEffect() throws Exception {
        var s = setUp("FCR2");
        String key = "key-" + UUID.randomUUID();

        List<PaymentService.InitiateResult> results = runConcurrently(
            () -> initiateAs(s.student.userId(), s.student.studentId(), new BigDecimal("500"), key),
            () -> initiateAs(s.student.userId(), s.student.studentId(), new BigDecimal("500"), key));

        List<UUID> paymentIds = results.stream()
            .filter(r -> r instanceof PaymentService.InitiateResult.Success)
            .map(r -> ((PaymentService.InitiateResult.Success) r).paymentId())
            .distinct().toList();
        // Do not claim exactly-once blocking behavior — a genuinely concurrent duplicate may see
        // StillProcessing instead of the replayed result — but there must never be more than one payment id.
        assertThat(paymentIds).hasSizeLessThanOrEqualTo(1);
        assertThat(results).allMatch(r -> r instanceof PaymentService.InitiateResult.Success
            || r instanceof PaymentService.InitiateResult.StillProcessing);
    }

    /** TEST 3: two concurrent requests with the SAME idempotency key but DIFFERENT payloads — the second (whichever loses the race) must be reported as a conflict, never silently processed as a retry. */
    @Test
    void concurrentSameKeyDifferentPayload_conflictDetected() throws Exception {
        var s = setUp("FCR3");
        String key = "key-" + UUID.randomUUID();

        List<PaymentService.InitiateResult> results = runConcurrently(
            () -> initiateAs(s.student.userId(), s.student.studentId(), new BigDecimal("111"), key),
            () -> initiateAs(s.student.userId(), s.student.studentId(), new BigDecimal("222"), key));

        long successCount = results.stream().filter(r -> r instanceof PaymentService.InitiateResult.Success).count();
        long conflictCount = results.stream().filter(r -> r instanceof PaymentService.InitiateResult.IdempotencyKeyConflict).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);
    }

    /** TEST 4: two concurrent balance adjustments on the SAME student account (via two independently-initiated, independently-succeeded payments) — the atomic SQL increment must never lose an update. */
    @Test
    void concurrentBalanceAdjustments_noLostUpdate() throws Exception {
        var s = setUp("FCR4");
        UUID payment1 = ((PaymentService.InitiateResult.Success)
            initiateAs(s.student.userId(), s.student.studentId(), new BigDecimal("100"), "key-" + UUID.randomUUID())).paymentId();
        UUID payment2 = ((PaymentService.InitiateResult.Success)
            initiateAs(s.student.userId(), s.student.studentId(), new BigDecimal("250"), "key-" + UUID.randomUUID())).paymentId();

        runConcurrently(
            () -> recordSuccessAs(s.tenant.adminUserId(), payment1),
            () -> recordSuccessAs(s.tenant.adminUserId(), payment2));

        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        var account = studentAccountService.get(s.tenant.tenantId(), s.student.studentId()).orElseThrow();
        TestAuthentication.clear();
        assertThat(account.balance()).isEqualByComparingTo("-350");
    }

    private PaymentService.WebhookResult recordSuccessAs(UUID userId, UUID paymentId) {
        TestAuthentication.authenticateAs(userId);
        try {
            return paymentService.recordWebhookEvent(paymentId, "evt-" + UUID.randomUUID(), "PAYMENT_SUCCEEDED", null);
        } finally {
            TestAuthentication.clear();
        }
    }

    private <T> List<T> runConcurrently(Callable<T> taskA, Callable<T> taskB) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> wrappedA = () -> {
                ready.countDown();
                start.await();
                return taskA.call();
            };
            Callable<T> wrappedB = () -> {
                ready.countDown();
                start.await();
                return taskB.call();
            };
            Future<T> fa = pool.submit(wrappedA);
            Future<T> fb = pool.submit(wrappedB);
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            return List.of(fa.get(15, TimeUnit.SECONDS), fb.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }
    }
}
