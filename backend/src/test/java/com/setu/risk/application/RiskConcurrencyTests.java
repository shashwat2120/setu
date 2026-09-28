package com.setu.risk.application;

import com.setu.identity.TestAuthentication;
import com.setu.risk.domain.InterventionStatus;
import com.setu.risk.domain.RiskScore;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Day 13 §Student Success Engine concurrency: real PostgreSQL, real
 * separate threads/transactions — same discipline as every other
 * concurrency test class in this codebase. Deliberately NOT {@code
 * @Transactional}: the status-checked conditional UPDATEs
 * ({@code RecommendationRepository}/{@code InterventionRepository}
 * #transitionIfStatus) need each thread to actually commit independently
 * for the race to be genuine.
 */
@SpringBootTest
class RiskConcurrencyTests {

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    RiskCalculationService riskCalculationService;
    @Autowired
    RecommendationService recommendationService;
    @Autowired
    InterventionService interventionService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
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

    private RiskCalculationService.RecalculateResult recalcAs(UUID userId, UUID studentId) {
        TestAuthentication.authenticateAs(userId);
        try {
            return riskCalculationService.recalculate(studentId);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** TEST 1: risk_scores has no uniqueness constraint (deliberately — append-only history), so two concurrent recalculations for the SAME student must both succeed with no lost data, each producing its own row. */
    @Test
    void concurrentRecalculation_bothSucceedAsSeparateAppendOnlyRows() throws Exception {
        var s = fixtures.provisionEnrolledScenario("RCC1");

        List<RiskCalculationService.RecalculateResult> results = runConcurrently(
            () -> recalcAs(s.tenant().adminUserId(), s.student().studentId()),
            () -> recalcAs(s.tenant().adminUserId(), s.student().studentId()));

        assertThat(results).allMatch(r -> r instanceof RiskCalculationService.RecalculateResult.Success);
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            assertThat(riskCalculationService.history(s.student().studentId())).hasSize(2);
        } finally {
            TestAuthentication.clear();
        }
    }

    private RecommendationService.DismissResult dismissAs(UUID userId, UUID recommendationId) {
        TestAuthentication.authenticateAs(userId);
        try {
            return recommendationService.dismiss(recommendationId);
        } finally {
            TestAuthentication.clear();
        }
    }

    private RecommendationService.DecisionResult acceptAs(UUID userId, UUID recommendationId) {
        TestAuthentication.authenticateAs(userId);
        try {
            return recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
        } finally {
            TestAuthentication.clear();
        }
    }

    /** TEST 2: two threads racing to accept vs. dismiss the SAME PROPOSED recommendation — the status-checked conditional UPDATE must let exactly one win; the loser gets a clean rejection, never a corrupted or double-transitioned row. */
    @Test
    void concurrentAcceptAndDismiss_exactlyOneWins() throws Exception {
        var s = fixtures.provisionEnrolledScenario("RCC2");
        fixtures.recordAttendanceSession(s, false);
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        fixtures.recordSubmission(s, -60);
        fixtures.recordLearningProgress(s, com.setu.learning.domain.LearningProgressState.IN_PROGRESS);
        RiskScore score = recalcAndFetch(s);
        UUID recommendationId;
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            recommendationId = recommendationService.listForRiskScore(score.id()).get(0).id();
        } finally {
            TestAuthentication.clear();
        }

        List<Object> results = runConcurrently(
            () -> acceptAs(s.tenant().adminUserId(), recommendationId),
            () -> dismissAs(s.tenant().adminUserId(), recommendationId));

        long successCount = results.stream()
            .filter(r -> r instanceof RecommendationService.DecisionResult.Success || r == RecommendationService.DismissResult.SUCCESS)
            .count();
        assertThat(successCount).isEqualTo(1);
    }

    private RiskScore recalcAndFetch(Day13TestFixtures.Scenario s) {
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            var result = (RiskCalculationService.RecalculateResult.Success) riskCalculationService.recalculate(s.student().studentId());
            return riskCalculationService.getById(result.riskScoreId()).orElseThrow();
        } finally {
            TestAuthentication.clear();
        }
    }

    private InterventionService.TransitionResult transitionAs(UUID userId, UUID interventionId, InterventionStatus target) {
        TestAuthentication.authenticateAs(userId);
        try {
            return interventionService.transition(interventionId, target);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** TEST 3: two threads racing to transition the SAME PLANNED intervention to two different terminal-ish states (IN_PROGRESS vs. CANCELLED) — the status-checked conditional UPDATE must let exactly one win. */
    @Test
    void concurrentInterventionTransitionRace_exactlyOneWins() throws Exception {
        var s = fixtures.provisionEnrolledScenario("RCC3");
        fixtures.recordAttendanceSession(s, false);
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        fixtures.recordSubmission(s, -60);
        fixtures.recordLearningProgress(s, com.setu.learning.domain.LearningProgressState.IN_PROGRESS);
        RiskScore score = recalcAndFetch(s);
        UUID recommendationId;
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            recommendationId = recommendationService.listForRiskScore(score.id()).get(0).id();
        } finally {
            TestAuthentication.clear();
        }
        UUID interventionId;
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var accepted = (RecommendationService.DecisionResult.Success)
                recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
            interventionId = accepted.interventionId();
        } finally {
            TestAuthentication.clear();
        }

        List<InterventionService.TransitionResult> results = runConcurrently(
            () -> transitionAs(s.faculty().userId(), interventionId, InterventionStatus.IN_PROGRESS),
            () -> transitionAs(s.faculty().userId(), interventionId, InterventionStatus.CANCELLED));

        long successCount = results.stream().filter(r -> r == InterventionService.TransitionResult.SUCCESS).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(results).anyMatch(r -> r == InterventionService.TransitionResult.INVALID_TRANSITION);
    }
}
