package com.setu.enrollment.application;

import com.setu.catalog.application.CoursePrerequisiteService;
import com.setu.catalog.domain.PrerequisiteRuleType;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A prerequisite is satisfied by a course the student has completed with a final grade, and the grade is what the rule looks at. Until a final grade
 * completed an enrollment nothing could satisfy a prerequisite that names a minimum grade; once a failing grade also completes one, a prerequisite
 * with no minimum must not be satisfied by it. Not {@code @Transactional}: the fixtures insert through guards that need real commits.
 */
@SpringBootTest
class FinalGradePrerequisiteTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    CoursePrerequisiteService coursePrerequisiteService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    /** A student who has finished the prerequisite course with {@code finalGrade}, asking for a section of the course that requires it. */
    private EnrollmentService.RequestResult requestAfterFinishing(String prefix, String minimumGrade, String finalGrade) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID prerequisiteCourse = fixtures.provisionCourse(tenant, dept, prefix + "PRE");
        UUID laterCourse = fixtures.provisionCourse(tenant, dept, prefix + "LATE");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID prerequisiteSection = day8Fixtures.provisionOpenSection(tenant, prerequisiteCourse, periodId, "S1", 10, null);
        UUID laterSection = day8Fixtures.provisionOpenSection(tenant, laterCourse, periodId, "S2", 10, null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");

        TestAuthentication.authenticateAs(tenant.adminUserId());
        coursePrerequisiteService.addPrerequisite(laterCourse, prerequisiteCourse, PrerequisiteRuleType.REQUIRED, minimumGrade);

        TestAuthentication.authenticateAs(student.userId());
        var first = (EnrollmentService.RequestResult.Enrolled) enrollmentService.request(student.studentId(), prerequisiteSection);
        // The final grade completes it the way the consumer does; the person on record is the college administrator here.
        enrollmentService.completeWithFinalGrade(first.enrollmentId(), tenant.tenantId(), finalGrade, tenant.adminUserId());

        TestAuthentication.authenticateAs(student.userId());
        return enrollmentService.request(student.studentId(), laterSection);
    }

    @Test
    void aPassSatisfiesAPrerequisiteWithNoMinimumGrade() {
        assertThat(requestAfterFinishing("FP1", null, "C")).isInstanceOf(EnrollmentService.RequestResult.Enrolled.class);
    }

    @Test
    void aFailDoesNotSatisfyAPrerequisiteWithNoMinimumGrade() {
        var result = requestAfterFinishing("FP2", null, "F");

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Rejected.class);
        assertThat(((EnrollmentService.RequestResult.Rejected) result).reason()).isEqualTo(EnrollmentService.RejectReason.PREREQUISITE_NOT_SATISFIED);
    }

    @Test
    void aGradeBelowTheMinimumDoesNotSatisfyIt() {
        var result = requestAfterFinishing("FP3", "B", "C");

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Rejected.class);
        assertThat(((EnrollmentService.RequestResult.Rejected) result).reason()).isEqualTo(EnrollmentService.RejectReason.PREREQUISITE_NOT_SATISFIED);
    }

    @Test
    void aGradeAtTheMinimumSatisfiesItEvenWithASign() {
        assertThat(requestAfterFinishing("FP4", "B", "B+")).isInstanceOf(EnrollmentService.RequestResult.Enrolled.class);
    }
}
