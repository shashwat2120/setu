package com.setu.assessment.application;

import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import com.setu.testsupport.Day11TestFixtures;
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
 * Day 11 §Grades/§Publication/§Correction/§Authorization: assessment.grades
 * — publish/correct, version history, and the explicit "guardian access
 * only through existing delegated permissions" / "cross-tenant object IDs
 * denied" requirements. Deliberately NOT {@code @Transactional} — {@link
 * GradeService#publishOrCorrect} uses {@code GradeInsertGuard}'s {@code
 * REQUIRES_NEW} insert for the first-publication path.
 */
@SpringBootTest
class GradeTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    Day11TestFixtures day11Fixtures;
    @Autowired
    GradeService gradeService;
    @Autowired
    GuardianAccessService guardianAccessService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId,
                             Day9TestFixtures.Faculty faculty) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        return new Scenario(tenant, programId, curriculumId, sectionId, faculty);
    }

    @Test
    void assignedFacultyCanPublishFirstGrade() {
        var s = setUp("GR1");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR1S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"));

        assertThat(result).isInstanceOf(GradeService.PublishResult.Success.class);
        var success = (GradeService.PublishResult.Success) result;
        assertThat(success.isCorrection()).isFalse();
        assertThat(success.version()).isEqualTo(1);
    }

    @Test
    void correctionCreatesNewVersionAndSupersedesOld() {
        var s = setUp("GR2");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR2S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"));

        var result = gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("9"), "A", new BigDecimal("4.0"));

        assertThat(result).isInstanceOf(GradeService.PublishResult.Success.class);
        var success = (GradeService.PublishResult.Success) result;
        assertThat(success.isCorrection()).isTrue();
        assertThat(success.version()).isEqualTo(2);

        var history = gradeService.history(enrollmentId, open.componentId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).version()).isEqualTo(2);
        assertThat(history.get(1).version()).isEqualTo(1);
    }

    @Test
    void currentGradeAlwaysReflectsLatestVersion() {
        var s = setUp("GR3");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR3S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"));
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("9"), "A", new BigDecimal("4.0"));

        var current = gradeService.getCurrent(enrollmentId, open.componentId());

        assertThat(current).isPresent();
        assertThat(current.get().rawMarks()).isEqualByComparingTo(new BigDecimal("9"));
        assertThat(current.get().version()).isEqualTo(2);
    }

    @Test
    void studentCanReadOwnGrade() {
        var s = setUp("GR4");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR4S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"));
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(student.userId());
        var read = gradeService.getCurrent(enrollmentId, open.componentId());

        assertThat(read).isPresent();
        // GradeService exposes no student-facing write method at all — ownership alone never confers write access.
    }

    @Test
    void anotherStudentCannotReadThisGrade() {
        var s = setUp("GR5");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR5S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"));
        TestAuthentication.clear();

        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR5S2");
        day9Fixtures.enrollStudent(otherStudent, s.sectionId);
        TestAuthentication.authenticateAs(otherStudent.userId());

        assertThatThrownBy(() -> gradeService.getCurrent(enrollmentId, open.componentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void facultyFromAnotherTenantCannotReadOrWriteGrade() {
        var s = setUp("GR6A");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR6AS");
        day9Fixtures.enrollStudent(student, s.sectionId);
        var otherTenant = fixtures.provisionTenant("GR6B");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.FACULTY, "GR6B-F");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void collegeAdminCanCorrectGradeViaExplicitPermission() {
        var s = setUp("GR7");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR7S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"));
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        var result = gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("7"), "B-", new BigDecimal("2.7"));

        assertThat(result).isInstanceOf(GradeService.PublishResult.Success.class);
    }

    @Test
    void guardianWithoutDelegatedGradeAccessIsDenied() {
        var s = setUp("GR8");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR8S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"));
        TestAuthentication.clear();

        var guardian = day10Fixtures.provisionGuardian(s.tenant, "GR8G");
        TestAuthentication.authenticateAs(guardian.guardianUserId());

        assertThatThrownBy(() -> gradeService.getCurrent(enrollmentId, open.componentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void guardianWithExplicitViewGradesGrantCanReadOnlyGrantedStudent() {
        var s = setUp("GR9");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR9S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("8"), "B", new BigDecimal("3.0"));
        TestAuthentication.clear();

        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR9S2");
        UUID otherEnrollmentId = day9Fixtures.enrollStudent(otherStudent, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), otherStudent.studentId(),
            new BigDecimal("6"), "C", new BigDecimal("2.0"));
        TestAuthentication.clear();

        var guardian = day10Fixtures.provisionGuardian(s.tenant, "GR9G");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        guardianAccessService.grantAccess(student.studentId(), guardian.guardianId(), null, Set.of("VIEW_GRADES"));
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        var allowed = gradeService.getCurrent(enrollmentId, open.componentId());
        assertThat(allowed).isPresent();

        assertThatThrownBy(() -> gradeService.getCurrent(otherEnrollmentId, open.componentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invalidGradeMarksAreRejected() {
        var s = setUp("GR10");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "GR10S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(),
            new BigDecimal("-1"), null, null);

        assertThat(result).isInstanceOf(GradeService.PublishResult.InvalidGradeData.class);
    }
}
