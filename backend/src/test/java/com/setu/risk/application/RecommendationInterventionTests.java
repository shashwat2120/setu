package com.setu.risk.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.risk.domain.InterventionStatus;
import com.setu.risk.domain.RecommendationStatus;
import com.setu.risk.domain.RiskScore;
import com.setu.testsupport.Day13TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 13 §Student Success Engine: recommendation (state-machines.md #22)
 * and intervention (state-machines.md #23) lifecycles against real
 * PostgreSQL, including the ERD's "accepting a recommendation creates a
 * linked intervention" rule and the COMPLETED-requires-an-action /
 * COLLEGE_ADMIN-only-reopen invariants.
 */
@SpringBootTest
class RecommendationInterventionTests {

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    Day7TestFixtures day7Fixtures;
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

    private RiskScore triggerHighRiskAsFaculty(Day13TestFixtures.Scenario s) {
        fixtures.recordAttendanceSession(s, false);
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        fixtures.recordSubmission(s, -60);
        fixtures.recordLearningProgress(s, com.setu.learning.domain.LearningProgressState.IN_PROGRESS);
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var result = (RiskCalculationService.RecalculateResult.Success) riskCalculationService.recalculate(s.student().studentId());
            return riskCalculationService.getById(result.riskScoreId()).orElseThrow();
        } finally {
            TestAuthentication.clear();
        }
    }

    private UUID soleRecommendationId(RiskScore score, UUID actingUserId) {
        TestAuthentication.authenticateAs(actingUserId);
        try {
            return recommendationService.listForRiskScore(score.id()).get(0).id();
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void acceptingARecommendation_createsALinkedPlannedIntervention() {
        var s = fixtures.provisionEnrolledScenario("RI1");
        RiskScore score = triggerHighRiskAsFaculty(s);
        UUID recommendationId = soleRecommendationId(score, s.faculty().userId());

        TestAuthentication.authenticateAs(s.faculty().userId());
        RecommendationService.DecisionResult.Success accepted;
        try {
            accepted = (RecommendationService.DecisionResult.Success)
                recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
        } finally {
            TestAuthentication.clear();
        }

        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var intervention = interventionService.getById(accepted.interventionId()).orElseThrow();
            assertThat(intervention.status()).isEqualTo(InterventionStatus.PLANNED);
            assertThat(intervention.recommendationId()).isEqualTo(recommendationId);
            assertThat(intervention.ownerUserId()).isEqualTo(s.faculty().userId());

            var afterAccept = recommendationService.listForRiskScore(score.id()).get(0);
            assertThat(afterAccept.status()).isEqualTo(RecommendationStatus.ACCEPTED);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void dismissingARecommendation_createsNoIntervention() {
        var s = fixtures.provisionEnrolledScenario("RI2");
        RiskScore score = triggerHighRiskAsFaculty(s);
        UUID recommendationId = soleRecommendationId(score, s.faculty().userId());

        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var result = recommendationService.dismiss(recommendationId);
            assertThat(result).isEqualTo(RecommendationService.DismissResult.SUCCESS);
            assertThat(recommendationService.listForRiskScore(score.id()).get(0).status()).isEqualTo(RecommendationStatus.DISMISSED);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void doubleAcceptingTheSameRecommendation_secondCallIsRejected() {
        var s = fixtures.provisionEnrolledScenario("RI3");
        RiskScore score = triggerHighRiskAsFaculty(s);
        UUID recommendationId = soleRecommendationId(score, s.faculty().userId());

        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var first = recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
            assertThat(first).isInstanceOf(RecommendationService.DecisionResult.Success.class);
            var second = recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
            assertThat(second).isInstanceOf(RecommendationService.DecisionResult.InvalidTransition.class);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void completingAnIntervention_requiresAtLeastOneRecordedAction() {
        var s = fixtures.provisionEnrolledScenario("RI4");
        RiskScore score = triggerHighRiskAsFaculty(s);
        UUID recommendationId = soleRecommendationId(score, s.faculty().userId());

        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var accepted = (RecommendationService.DecisionResult.Success)
                recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
            UUID interventionId = accepted.interventionId();

            assertThat(interventionService.transition(interventionId, InterventionStatus.IN_PROGRESS))
                .isEqualTo(InterventionService.TransitionResult.SUCCESS);

            var tooEarly = interventionService.transition(interventionId, InterventionStatus.COMPLETED);
            assertThat(tooEarly).isEqualTo(InterventionService.TransitionResult.COMPLETION_REQUIRES_ACTION);

            assertThat(interventionService.addAction(interventionId, "Met with student", "Discussed attendance plan"))
                .isEqualTo(InterventionService.AddActionResult.SUCCESS);

            assertThat(interventionService.transition(interventionId, InterventionStatus.COMPLETED))
                .isEqualTo(InterventionService.TransitionResult.SUCCESS);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void reopeningACompletedIntervention_requiresCollegeAdmin() {
        var s = fixtures.provisionEnrolledScenario("RI5");
        RiskScore score = triggerHighRiskAsFaculty(s);
        UUID recommendationId = soleRecommendationId(score, s.faculty().userId());

        UUID interventionId;
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var accepted = (RecommendationService.DecisionResult.Success)
                recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
            interventionId = accepted.interventionId();
            interventionService.transition(interventionId, InterventionStatus.IN_PROGRESS);
            interventionService.addAction(interventionId, "Follow-up call", null);
            interventionService.transition(interventionId, InterventionStatus.COMPLETED);
        } finally {
            TestAuthentication.clear();
        }

        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var ownerReopenAttempt = interventionService.transition(interventionId, InterventionStatus.IN_PROGRESS);
            assertThat(ownerReopenAttempt).isEqualTo(InterventionService.TransitionResult.REOPEN_REQUIRES_ADMIN);
        } finally {
            TestAuthentication.clear();
        }

        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            var adminReopen = interventionService.transition(interventionId, InterventionStatus.IN_PROGRESS);
            assertThat(adminReopen).isEqualTo(InterventionService.TransitionResult.SUCCESS);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void cancellingAnIntervention_isTerminalAndBlocksFurtherActions() {
        var s = fixtures.provisionEnrolledScenario("RI6");
        RiskScore score = triggerHighRiskAsFaculty(s);
        UUID recommendationId = soleRecommendationId(score, s.faculty().userId());

        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var accepted = (RecommendationService.DecisionResult.Success)
                recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
            UUID interventionId = accepted.interventionId();

            assertThat(interventionService.transition(interventionId, InterventionStatus.CANCELLED))
                .isEqualTo(InterventionService.TransitionResult.SUCCESS);
            assertThat(interventionService.addAction(interventionId, "too late", null))
                .isEqualTo(InterventionService.AddActionResult.TERMINAL_STATE);
            assertThat(interventionService.transition(interventionId, InterventionStatus.IN_PROGRESS))
                .isEqualTo(InterventionService.TransitionResult.INVALID_TRANSITION);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void collegeAdminWhoOwnsAnInterventionCanStillActOnItViaTenantWidePermission() {
        // Day 16 §student-success: real gap DemoDataSeeder's own risk-data verification found — a COLLEGE_ADMIN
        // who accepts a recommendation themselves (rather than the advising FACULTY) becomes that intervention's
        // owner, but holds only the tenant-wide MANAGE_INTERVENTIONS_ALL_SECTIONS, never the narrow owner-scoped
        // MANAGE_INTERVENTIONS a plain advisor holds. assertOwnerOrAdmin must fall back to the tenant-wide check
        // rather than failing outright once the owner branch's narrow permission is absent.
        var s = fixtures.provisionEnrolledScenario("RI8");
        RiskScore score = triggerHighRiskAsFaculty(s);
        UUID recommendationId = soleRecommendationId(score, s.tenant().adminUserId());

        UUID interventionId;
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            var accepted = (RecommendationService.DecisionResult.Success)
                recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
            interventionId = accepted.interventionId();
            assertThat(interventionService.getById(interventionId).orElseThrow().ownerUserId())
                .isEqualTo(s.tenant().adminUserId());

            assertThat(interventionService.transition(interventionId, InterventionStatus.IN_PROGRESS))
                .isEqualTo(InterventionService.TransitionResult.SUCCESS);
            assertThat(interventionService.addAction(interventionId, "Advisor meeting scheduled", null))
                .isEqualTo(InterventionService.AddActionResult.SUCCESS);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void unrelatedFacultyCannotActOnAnInterventionTheyDoNotOwn() {
        var s = fixtures.provisionEnrolledScenario("RI7");
        RiskScore score = triggerHighRiskAsFaculty(s);
        UUID recommendationId = soleRecommendationId(score, s.faculty().userId());

        UUID interventionId;
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var accepted = (RecommendationService.DecisionResult.Success)
                recommendationService.accept(recommendationId, LocalDate.now().plusDays(7));
            interventionId = accepted.interventionId();
        } finally {
            TestAuthentication.clear();
        }

        UUID otherFacultyUserId = day7Fixtures.provisionUserWithRole(s.tenant().tenantId(), RoleCode.FACULTY, "RI7-OF");
        TestAuthentication.authenticateAs(otherFacultyUserId);
        try {
            assertThatThrownBy(() -> interventionService.transition(interventionId, InterventionStatus.IN_PROGRESS))
                .isInstanceOf(AccessDeniedException.class);
        } finally {
            TestAuthentication.clear();
        }
    }
}
