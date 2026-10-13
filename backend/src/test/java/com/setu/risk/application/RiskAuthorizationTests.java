package com.setu.risk.application;

import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.risk.domain.RiskScore;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day13TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
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
 * Day 13 §Student Success Engine: authorization/tenant-isolation matrix
 * (docs/authorization-matrix.md rows "Risk score / factors" and
 * "Recommendation / intervention") — own student, advisor faculty
 * (section-assignment-derived), unrelated faculty, tenant admin, guardian
 * with/without an explicit {@code VIEW_RISK_SCORE} grant, and cross-tenant
 * isolation.
 */
@SpringBootTest
class RiskAuthorizationTests {

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    Day7TestFixtures day7Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    RiskCalculationService riskCalculationService;
    @Autowired
    RecommendationService recommendationService;
    @Autowired
    GuardianAccessService guardianAccessService;

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
    void studentCanViewOwnRiskScore() {
        var s = fixtures.provisionEnrolledScenario("RA1");
        recalcAsAdmin(s);

        TestAuthentication.authenticateAs(s.student().userId());
        try {
            assertThat(riskCalculationService.getCurrent(s.student().studentId())).isPresent();
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void studentCannotViewAnotherStudentsRiskScore() {
        var s = fixtures.provisionEnrolledScenario("RA2");
        recalcAsAdmin(s);
        var other = fixtures.provisionEnrolledScenario("RA2B");

        TestAuthentication.authenticateAs(other.student().userId());
        try {
            assertThatThrownBy(() -> riskCalculationService.getCurrent(s.student().studentId()))
                .isInstanceOf(AccessDeniedException.class);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void advisorFacultyCanRecalculateAndViewTheirAdvisee() {
        var s = fixtures.provisionEnrolledScenario("RA3");

        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var result = riskCalculationService.recalculate(s.student().studentId());
            assertThat(result).isInstanceOf(RiskCalculationService.RecalculateResult.Success.class);
            assertThat(riskCalculationService.getCurrent(s.student().studentId())).isPresent();
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void unrelatedFacultyCannotRecalculateOrViewAStudentTheyDoNotAdvise() {
        var s = fixtures.provisionEnrolledScenario("RA4");
        recalcAsAdmin(s);
        UUID unrelatedFacultyUserId = day7Fixtures.provisionUserWithRole(s.tenant().tenantId(), RoleCode.FACULTY, "RA4-UF");

        TestAuthentication.authenticateAs(unrelatedFacultyUserId);
        try {
            assertThatThrownBy(() -> riskCalculationService.recalculate(s.student().studentId()))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> riskCalculationService.getCurrent(s.student().studentId()))
                .isInstanceOf(AccessDeniedException.class);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void collegeAdminCanViewAndManageAnyStudentTenantWide() {
        var s = fixtures.provisionEnrolledScenario("RA5");

        recalcAsAdmin(s);

        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            assertThat(riskCalculationService.getCurrent(s.student().studentId())).isPresent();
            assertThat(riskCalculationService.history(s.student().studentId())).hasSize(1);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void crossTenantAdminCannotViewOrManageAnotherTenantsStudent() {
        var s = fixtures.provisionEnrolledScenario("RA6A");
        recalcAsAdmin(s);
        var otherTenant = day7Fixtures.provisionTenant("RA6B");

        TestAuthentication.authenticateAs(otherTenant.adminUserId());
        try {
            assertThatThrownBy(() -> riskCalculationService.recalculate(s.student().studentId()))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> riskCalculationService.getCurrent(s.student().studentId()))
                .isInstanceOf(AccessDeniedException.class);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void guardianWithGrantCanViewRiskScoreButNotRecommendations() {
        var s = fixtures.provisionEnrolledScenario("RA7");
        fixtures.recordGrade(s, "F", BigDecimal.ZERO); // MEDIUM band -> auto-recommendation exists to attempt to view
        RiskScore score = recalcAsAdmin(s);

        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "RA7G");
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            guardianAccessService.grantAccess(s.student().studentId(), guardian.guardianId(), null, Set.of("VIEW_RISK_SCORE"));
        } finally {
            TestAuthentication.clear();
        }

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        try {
            assertThat(riskCalculationService.getCurrent(s.student().studentId())).isPresent();
            assertThatThrownBy(() -> recommendationService.listForRiskScore(score.id()))
                .isInstanceOf(AccessDeniedException.class);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void guardianWithoutGrantCannotViewRiskScore() {
        var s = fixtures.provisionEnrolledScenario("RA8");
        recalcAsAdmin(s);
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "RA8G");

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        try {
            assertThatThrownBy(() -> riskCalculationService.getCurrent(s.student().studentId()))
                .isInstanceOf(AccessDeniedException.class);
        } finally {
            TestAuthentication.clear();
        }
    }
}
