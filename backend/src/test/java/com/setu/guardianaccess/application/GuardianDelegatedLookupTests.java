package com.setu.guardianaccess.application;

import com.setu.assessment.application.AssessmentComponentService;
import com.setu.enrollment.application.CourseSectionService;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day11TestFixtures;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guardian dashboard resolves each schedule row's section and each assessment's components on the student's
 * behalf ({@code guardianForStudentId}). Regression: both used to 403 for a guardian who held the right delegation,
 * so the dashboard showed "—" for the course and no grade components. A delegation must unlock exactly what it
 * names, for exactly that student, and nothing else.
 */
@SpringBootTest
class GuardianDelegatedLookupTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    Day11TestFixtures day11Fixtures;
    @Autowired
    GuardianAccessService guardianAccessService;
    @Autowired
    CourseSectionService courseSectionService;
    @Autowired
    AssessmentComponentService assessmentComponentService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private void grant(Day13TestFixtures.Scenario s, Day10TestFixtures.Guardian guardian, Set<String> permissions) {
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            guardianAccessService.grantAccess(s.student().studentId(), guardian.guardianId(), null, permissions);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void guardianWithScheduleDelegationCanResolveTheEnrolledSection() {
        var s = day13Fixtures.provisionEnrolledScenario("GDL1");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "GDL1G");
        grant(s, guardian, Set.of("VIEW_SCHEDULE"));

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        assertThat(courseSectionService.get(s.sectionId(), s.student().studentId())).isPresent();
    }

    @Test
    void guardianWithoutAnyGrantIsDeniedTheSection() {
        var s = day13Fixtures.provisionEnrolledScenario("GDL2");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "GDL2G");

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        assertThatThrownBy(() -> courseSectionService.get(s.sectionId(), s.student().studentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aGrantForADifferentPermissionDoesNotUnlockTheSection() {
        var s = day13Fixtures.provisionEnrolledScenario("GDL3");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "GDL3G");
        grant(s, guardian, Set.of("VIEW_ATTENDANCE"));

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        assertThatThrownBy(() -> courseSectionService.get(s.sectionId(), s.student().studentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aDelegationForOneStudentCannotBeUsedToReadASectionTheyAreNotEnrolledIn() {
        var s = day13Fixtures.provisionEnrolledScenario("GDL4");
        var other = day13Fixtures.provisionEnrolledScenario("GDL4X"); // a different tenant's section entirely
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "GDL4G");
        grant(s, guardian, Set.of("VIEW_SCHEDULE"));

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        assertThatThrownBy(() -> courseSectionService.get(other.sectionId(), s.student().studentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void guardianWithGradesDelegationCanListComponentsButWithoutItCannot() {
        var s = day13Fixtures.provisionEnrolledScenario("GDL5");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty().userId(), s.sectionId(), "Quiz-GDL5");
        var granted = day10Fixtures.provisionGuardian(s.tenant(), "GDL5G");
        var ungranted = day10Fixtures.provisionGuardian(s.tenant(), "GDL5N");
        grant(s, granted, Set.of("VIEW_GRADES"));
        UUID studentId = s.student().studentId();

        TestAuthentication.authenticateAs(granted.guardianUserId());
        assertThat(assessmentComponentService.listForAssessment(open.assessmentId(), studentId)).isNotEmpty();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(ungranted.guardianUserId());
        assertThatThrownBy(() -> assessmentComponentService.listForAssessment(open.assessmentId(), studentId))
            .isInstanceOf(AccessDeniedException.class);
    }
}
