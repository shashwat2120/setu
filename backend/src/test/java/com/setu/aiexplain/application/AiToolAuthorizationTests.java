package com.setu.aiexplain.application;

import com.setu.career.application.JobService;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.risk.application.RecommendationService;
import com.setu.risk.application.RiskCalculationService;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day13TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 14 §21 security tests, exercised directly against {@link
 * AiToolService} rather than through a live model call: the security
 * property under test is "the tool itself refuses unauthorized data,
 * regardless of what the model says" — an LLM's phrasing is not
 * deterministic, but whether {@link AiToolService#getStudentAttendance}
 * returns real data or a {@link AiToolService.ToolError} is. Items 8-10
 * and 13-14 of the §21 list, which genuinely require a live model, are in
 * {@code AiChatIntegrationTests} instead.
 * <p>
 * Deliberately NOT {@code @Transactional} — several fixture calls span
 * multiple committed transactions (guardian grants, attendance sessions).
 */
@SpringBootTest
class AiToolAuthorizationTests {

    @Autowired
    Day7TestFixtures day7Fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    AiToolService aiToolService;
    @Autowired
    GuardianAccessService guardianAccessService;
    @Autowired
    JobService jobService;
    @Autowired
    RiskCalculationService riskCalculationService;
    @Autowired
    RecommendationService recommendationService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private Object callAs(UUID userId, java.util.function.Supplier<Object> call) {
        TestAuthentication.authenticateAs(userId);
        try {
            return call.get();
        } finally {
            TestAuthentication.clear();
        }
    }

    // ---- 1/2: own data PASS, another student's data DENY --------------------------------------

    @Test
    void studentCanReadOwnAttendance() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT1");
        day13Fixtures.recordAttendanceSession(s, true);

        Object result = callAs(s.student().userId(), () -> aiToolService.getStudentAttendance(null));

        assertThat(result).isInstanceOf(AiToolService.AttendanceResult.class);
        assertThat(((AiToolService.AttendanceResult) result).percentage()).isEqualTo(100.0);
    }

    @Test
    void studentCannotReadAnotherStudentsAttendance() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT2");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant(), s.programId(), s.curriculumId(), "AIT2O");
        day9Fixtures.enrollStudent(other, s.sectionId());

        Object result = callAs(other.userId(), () -> aiToolService.getStudentAttendance(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    // ---- 3/4: guardian without/with delegation --------------------------------------------------

    @Test
    void guardianWithoutDelegationCannotReadAttendance() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT3");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "AIT3G");

        Object result = callAs(guardian.guardianUserId(), () -> aiToolService.getStudentAttendance(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    @Test
    void guardianWithDelegationCanReadAttendance() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT4");
        day13Fixtures.recordAttendanceSession(s, true);
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "AIT4G");
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            guardianAccessService.grantAccess(s.student().studentId(), guardian.guardianId(), null, Set.of("VIEW_ATTENDANCE"));
        } finally {
            TestAuthentication.clear();
        }

        Object result = callAs(guardian.guardianUserId(), () -> aiToolService.getStudentAttendance(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.AttendanceResult.class);
    }

    // ---- 5/6: faculty advisor PASS, unrelated faculty DENY ----------------------------------------

    @Test
    void assignedFacultyCanReadAdviseesAttendance() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT5");
        day13Fixtures.recordAttendanceSession(s, true);

        Object result = callAs(s.faculty().userId(), () -> aiToolService.getStudentAttendance(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.AttendanceResult.class);
    }

    @Test
    void unrelatedFacultyCannotReadAttendance() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT6");
        UUID unrelatedFacultyUserId = day7Fixtures.provisionUserWithRole(s.tenant().tenantId(), RoleCode.FACULTY, "AIT6-UF");

        Object result = callAs(unrelatedFacultyUserId, () -> aiToolService.getStudentAttendance(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    // ---- 7: cross-tenant DENY ----------------------------------------------------------------------

    @Test
    void crossTenantUserCannotReadAnotherTenantsStudentData() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT7A");
        var otherTenant = day7Fixtures.provisionTenant("AIT7B");

        Object result = callAs(otherTenant.adminUserId(), () -> aiToolService.getStudentAttendance(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    // ---- 12: invalid tool parameters DENY -------------------------------------------------------------

    @Test
    void malformedStudentIdIsRejectedNotThrown() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT8");

        Object result = callAs(s.student().userId(), () -> aiToolService.getStudentAttendance("not-a-uuid"));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    @Test
    void invalidJobIdForSkillGapIsRejected() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT9");

        Object result = callAs(s.student().userId(), () -> aiToolService.getSkillGap("not-a-uuid", null));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    // ---- getAcademicProgress / getAssessmentResults / getUpcomingDeadlines / recommendLearningResources: same authorization shape ----

    @Test
    void academicProgressToolDeniesUnrelatedFaculty() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT10");
        UUID unrelatedFacultyUserId = day7Fixtures.provisionUserWithRole(s.tenant().tenantId(), RoleCode.FACULTY, "AIT10-UF");

        Object result = callAs(unrelatedFacultyUserId, () -> aiToolService.getAcademicProgress(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    @Test
    void deadlinesToolAllowsSelfAndDeniesOthers() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT11");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant(), s.programId(), s.curriculumId(), "AIT11O");

        Object own = callAs(s.student().userId(), () -> aiToolService.getUpcomingDeadlines(null));
        Object denied = callAs(other.userId(), () -> aiToolService.getUpcomingDeadlines(s.student().studentId().toString()));

        assertThat(own).isInstanceOf(java.util.List.class);
        assertThat(denied).isInstanceOf(AiToolService.ToolError.class);
    }

    @Test
    void recommendLearningResourcesNeverInventsLessons() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT12");

        @SuppressWarnings("unchecked")
        var result = (java.util.List<AiToolService.LearningResource>)
            callAs(s.student().userId(), () -> aiToolService.recommendLearningResources(null));

        // No modules/lessons were ever created for this section — a real absence of data, not an omission.
        assertThat(result).isEmpty();
    }

    @Test
    void skillGapIsSelfOrAdminOnly_facultyIsDeniedEvenAsAdvisor() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT13");
        UUID placementOfficer = day12Fixtures.provisionPlacementOfficer(s.tenant(), "AIT13PO");
        UUID jobId;
        TestAuthentication.authenticateAs(placementOfficer);
        try {
            jobId = ((JobService.CreateResult.Success) jobService.create(s.tenant().tenantId(), "Role", "Employer", null)).jobId();
        } finally {
            TestAuthentication.clear();
        }

        // Faculty is genuinely an advisor of this student, but career data has no advisor access at all
        // (Day 12's established authorization matrix) — this must still deny, with a REAL job id so the
        // denial actually comes from the authorization check, not a "job not found" short-circuit.
        Object result = callAs(s.faculty().userId(), () -> aiToolService.getSkillGap(jobId.toString(), s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    // ---- transcript / fee tools: authorization is delegated to the owning service, so prove it still bites ----

    @Test
    void studentCanReadOwnTranscript() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT15");

        Object result = callAs(s.student().userId(), () -> aiToolService.getTranscript(null));

        assertThat(result).isInstanceOf(AiToolService.TranscriptResult.class);
    }

    @Test
    void studentCannotReadAnotherStudentsTranscript() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT16");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant(), s.programId(), s.curriculumId(), "AIT16O");

        Object result = callAs(other.userId(), () -> aiToolService.getTranscript(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    @Test
    void guardianWithoutDelegationCannotReadTranscript() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT17");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "AIT17G");

        Object result = callAs(guardian.guardianUserId(), () -> aiToolService.getTranscript(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    @Test
    void studentCanReadOwnFeeStatus() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT18");

        Object result = callAs(s.student().userId(), () -> aiToolService.getFeeStatus(null));

        assertThat(result).isInstanceOf(AiToolService.FeeStatusResult.class);
        assertThat(((AiToolService.FeeStatusResult) result).outstanding()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void studentCannotReadAnotherStudentsFeeStatus() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT19");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant(), s.programId(), s.curriculumId(), "AIT19O");

        Object result = callAs(other.userId(), () -> aiToolService.getFeeStatus(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    @Test
    void guardianWithoutDelegationCannotReadFeeStatus() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT20");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "AIT20G");

        Object result = callAs(guardian.guardianUserId(), () -> aiToolService.getFeeStatus(s.student().studentId().toString()));

        assertThat(result).isInstanceOf(AiToolService.ToolError.class);
    }

    /**
     * Day 14 §5: "Guardian cannot access recommendations/interventions
     * merely because risk-score access exists." A VIEW_RISK_SCORE grant
     * legitimately unlocks the risk band/factors surfaced by {@code
     * getAcademicProgress} (matches docs/authorization-matrix.md's "Risk
     * score / factors" row: guardian read own, if granted) — but the
     * separate "Recommendation / intervention" row gives guardians no
     * access at all, and {@code ai-explain} has no tool that exposes
     * recommendations/interventions in the first place (only {@link
     * RiskCalculationService#getCurrent}/{@code factorsFor} are wired into
     * {@link AiToolService}, never {@link RecommendationService}). This
     * test proves both halves: the AI tool legitimately returns the risk
     * band for this guardian, and the underlying recommendation data
     * remains denied to them regardless.
     */
    @Test
    void guardianWithRiskScoreGrant_seesRiskBandButNeverRecommendations() {
        var s = day13Fixtures.provisionEnrolledScenario("AIT14");
        day13Fixtures.recordAttendanceSession(s, false);
        day13Fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        day13Fixtures.recordSubmission(s, -60);
        day13Fixtures.recordLearningProgress(s, com.setu.learning.domain.LearningProgressState.IN_PROGRESS);
        UUID riskScoreId;
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            var result = (RiskCalculationService.RecalculateResult.Success) riskCalculationService.recalculate(s.student().studentId());
            riskScoreId = result.riskScoreId();
        } finally {
            TestAuthentication.clear();
        }

        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "AIT14G");
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            guardianAccessService.grantAccess(s.student().studentId(), guardian.guardianId(), null, Set.of("VIEW_RISK_SCORE"));
        } finally {
            TestAuthentication.clear();
        }

        Object progress = callAs(guardian.guardianUserId(), () -> aiToolService.getAcademicProgress(s.student().studentId().toString()));
        assertThat(progress).isInstanceOf(AiToolService.AcademicProgressResult.class);
        var risk = ((AiToolService.AcademicProgressResult) progress).risk();
        assertThat(risk).isNotNull();
        assertThat(risk.band()).isEqualTo("HIGH");

        UUID scoreId = riskScoreId;
        TestAuthentication.authenticateAs(guardian.guardianUserId());
        try {
            assertThatThrownBy(() -> recommendationService.listForRiskScore(scoreId)).isInstanceOf(AccessDeniedException.class);
        } finally {
            TestAuthentication.clear();
        }
    }
}
