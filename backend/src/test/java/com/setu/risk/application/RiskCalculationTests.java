package com.setu.risk.application;

import com.setu.identity.TestAuthentication;
import com.setu.learning.domain.LearningProgressState;
import com.setu.risk.domain.RiskBand;
import com.setu.risk.domain.RiskScore;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 13 §Student Success Engine: {@link RiskCalculationService} against
 * real PostgreSQL — deterministic scoring end to end (real attendance/
 * grade/submission/progress rows through their OWN owning services, never
 * fabricated), append-only history, reproducibility (stored hash actually
 * verifies the stored snapshot), and the auto-recommendation side effect
 * on a band change.
 */
@SpringBootTest
class RiskCalculationTests {

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    RiskCalculationService riskCalculationService;
    @Autowired
    RecommendationService recommendationService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private RiskScore recalcAsAdmin(Day13TestFixtures.Scenario s) {
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            var result = (RiskCalculationService.RecalculateResult.Success) riskCalculationService.recalculate(s.student().studentId());
            return riskCalculationService.getById(result.riskScoreId()).orElseThrow();
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void freshlyEnrolledStudentWithNoData_isNeutralLowRisk() {
        var s = fixtures.provisionEnrolledScenario("RC1");

        RiskScore score = recalcAsAdmin(s);

        assertThat(score.band()).isEqualTo(RiskBand.LOW);
        assertThat(score.score()).isEqualByComparingTo("0.000");
        assertThat(score.ruleVersion()).isEqualTo("v1");
    }

    @Test
    void healthyStudent_scoresLowAcrossAllSignals() {
        var s = fixtures.provisionEnrolledScenario("RC2");
        fixtures.recordAttendanceSession(s, true);
        fixtures.recordGrade(s, "A", new BigDecimal("4.0"));
        fixtures.recordSubmission(s, 60); // due in the future -> on-time
        fixtures.recordLearningProgress(s, LearningProgressState.COMPLETED);

        RiskScore score = recalcAsAdmin(s);

        assertThat(score.band()).isEqualTo(RiskBand.LOW);
        assertThat(score.score()).isEqualByComparingTo("0.000");
    }

    @Test
    void strugglingStudent_scoresHighAcrossAllSignals() {
        var s = fixtures.provisionEnrolledScenario("RC3");
        fixtures.recordAttendanceSession(s, false); // absent -> full attendance risk
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        fixtures.recordSubmission(s, -60); // due in the past -> late
        fixtures.recordLearningProgress(s, LearningProgressState.IN_PROGRESS);

        RiskScore score = recalcAsAdmin(s);

        assertThat(score.band()).isEqualTo(RiskBand.HIGH);
        assertThat(score.score()).isEqualByComparingTo("90.000");
    }

    @Test
    void failingGradeAlone_landsInMediumBand() {
        var s = fixtures.provisionEnrolledScenario("RC4");
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);

        RiskScore score = recalcAsAdmin(s);

        assertThat(score.band()).isEqualTo(RiskBand.MEDIUM);
        assertThat(score.score()).isEqualByComparingTo("30.000");
    }

    @Test
    void everyFactorHasEvidenceReferencingTheStudentsOwnEnrollment() {
        var s = fixtures.provisionEnrolledScenario("RC5");
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);

        RiskScore score = recalcAsAdmin(s);
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        List<com.setu.risk.domain.RiskFactor> factors;
        try {
            factors = riskCalculationService.factorsFor(score.id());
        } finally {
            TestAuthentication.clear();
        }

        assertThat(factors).isNotEmpty();
        assertThat(factors).allSatisfy(f -> {
            assertThat(f.evidenceRefs()).contains(s.enrollmentId().toString());
            assertThat(f.evidenceRefs()).contains("\"enrollments\"");
        });
    }

    @Test
    void featureSnapshotHash_isReproducibleFromTheStoredSnapshot() throws Exception {
        var s = fixtures.provisionEnrolledScenario("RC6");
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);

        RiskScore score = recalcAsAdmin(s);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] recomputed = digest.digest(score.featureSnapshot().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String recomputedHex = HexFormat.of().formatHex(recomputed);

        assertThat(recomputedHex).isEqualTo(score.featureSnapshotHash());
    }

    @Test
    void recalculation_isAppendOnly_neverOverwritesThePreviousRow() {
        var s = fixtures.provisionEnrolledScenario("RC7");

        RiskScore first = recalcAsAdmin(s);
        RiskScore second = recalcAsAdmin(s);

        assertThat(first.id()).isNotEqualTo(second.id());
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        List<RiskScore> history;
        try {
            history = riskCalculationService.history(s.student().studentId());
        } finally {
            TestAuthentication.clear();
        }
        assertThat(history).hasSize(2);
    }

    @Test
    void bandRisingIntoHigh_autoProposesAnUrgentRecommendation() {
        var s = fixtures.provisionEnrolledScenario("RC8");
        fixtures.recordAttendanceSession(s, false);
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        fixtures.recordSubmission(s, -60);
        fixtures.recordLearningProgress(s, LearningProgressState.IN_PROGRESS);

        RiskScore score = recalcAsAdmin(s);
        assertThat(score.band()).isEqualTo(RiskBand.HIGH);

        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        List<com.setu.risk.domain.Recommendation> recs;
        try {
            recs = recommendationService.listForRiskScore(score.id());
        } finally {
            TestAuthentication.clear();
        }
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).actionCode()).isEqualTo("URGENT_ADVISOR_MEETING");
        assertThat(recs.get(0).generatedBy()).isEqualTo(com.setu.risk.domain.GeneratedBy.RULE);
    }

    @Test
    void bandUnchangedOnRecalculation_doesNotProposeADuplicateRecommendation() {
        var s = fixtures.provisionEnrolledScenario("RC9");
        fixtures.recordGrade(s, "F", BigDecimal.ZERO); // MEDIUM band, unchanged across both calls

        RiskScore first = recalcAsAdmin(s);
        RiskScore second = recalcAsAdmin(s);
        assertThat(first.band()).isEqualTo(RiskBand.MEDIUM);
        assertThat(second.band()).isEqualTo(RiskBand.MEDIUM);

        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        List<com.setu.risk.domain.Recommendation> recsForFirst;
        List<com.setu.risk.domain.Recommendation> recsForSecond;
        try {
            recsForFirst = recommendationService.listForRiskScore(first.id());
            recsForSecond = recommendationService.listForRiskScore(second.id());
        } finally {
            TestAuthentication.clear();
        }
        assertThat(recsForFirst).hasSize(1); // proposed on the FIRST calculation (LOW -> MEDIUM is a real change)
        assertThat(recsForSecond).isEmpty(); // second calculation: band didn't change, nothing new proposed
    }

    // --- Day 16 §college-admin: listCurrentForTenant ---

    @Test
    void listCurrentForTenant_collapsesHistoryToOneLatestRowPerStudent_andIsTenantScoped() {
        var s = fixtures.provisionEnrolledScenario("RC10");
        var other = fixtures.provisionEnrolledScenario("RC10B");
        recalcAsAdmin(s); // first calculation: LOW
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        RiskScore latest = recalcAsAdmin(s); // second calculation: MEDIUM — same student, new row

        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        List<RiskScore> forTenant;
        try {
            forTenant = riskCalculationService.listCurrentForTenant(s.tenant().tenantId());
        } finally {
            TestAuthentication.clear();
        }

        assertThat(forTenant).hasSize(1); // two calculations for the same student collapse to one, the latest
        assertThat(forTenant.get(0).id()).isEqualTo(latest.id());
        assertThat(forTenant.get(0).band()).isEqualTo(RiskBand.MEDIUM);
        assertThat(forTenant).extracting(RiskScore::studentId).doesNotContain(other.student().studentId());
    }

    @Test
    void listCurrentForTenant_selfViewPermissionIsNotEnoughForTheTenantWideList() {
        var s = fixtures.provisionEnrolledScenario("RC11");
        recalcAsAdmin(s);

        TestAuthentication.authenticateAs(s.student().userId());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> riskCalculationService.listCurrentForTenant(s.tenant().tenantId()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {
            TestAuthentication.clear();
        }
    }
}
