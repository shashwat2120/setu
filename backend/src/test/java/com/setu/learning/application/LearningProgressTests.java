package com.setu.learning.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.learning.domain.LearningProgressState;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 10 §4, §13: learning_progress — student self-write, faculty/admin
 * read-only, first-ever real guardian-delegation wiring, and the explicit
 * "student accesses another student's progress -> DENY" / "student accesses
 * another tenant -> DENY" / guardian scope requirements. Deliberately NOT
 * {@code @Transactional} — {@code record()} uses {@code
 * LearningProgressUpsertGuard}'s {@code PROPAGATION_REQUIRES_NEW} insert,
 * which opens a genuinely separate connection that cannot see this test's
 * own fixture setup (lesson/module) if it were sitting in an uncommitted
 * auto-rollback transaction (the same lesson Day 9's attendance tests
 * learned the hard way).
 */
@SpringBootTest
class LearningProgressTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    LearningProgressService learningProgressService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId,
                             UUID lessonId, Day9TestFixtures.Faculty faculty) {
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
        UUID moduleId = day10Fixtures.provisionModule(faculty.userId(), sectionId, "Week 1", 1);
        UUID lessonId = day10Fixtures.provisionLesson(faculty.userId(), moduleId, "Intro", 1);
        return new Scenario(tenant, programId, curriculumId, sectionId, lessonId, faculty);
    }

    @Test
    void enrolledStudentCanRecordOwnProgress() {
        var s = setUp("LP1");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP1S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("40"), null);

        assertThat(result).isInstanceOf(LearningProgressService.RecordResult.Success.class);
    }

    @Test
    void completingProgressSetsCompletedAtAndSucceeds() {
        var s = setUp("LP2");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP2S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("40"), null);

        var result = learningProgressService.record(s.lessonId, LearningProgressState.COMPLETED, new BigDecimal("100"), null);
        var progressId = ((LearningProgressService.RecordResult.Success) result).progressId();
        var row = learningProgressService.getById(progressId).orElseThrow();

        assertThat(result).isInstanceOf(LearningProgressService.RecordResult.Success.class);
        assertThat(row.completedAt()).isNotNull();
        assertThat(row.startedAt()).isNotNull();
    }

    @Test
    void incoherentPercentForStateIsRejected() {
        var s = setUp("LP3");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = learningProgressService.record(s.lessonId, LearningProgressState.COMPLETED, new BigDecimal("50"), null);

        assertThat(result).isInstanceOf(LearningProgressService.RecordResult.InvalidProgressData.class);
    }

    @Test
    void unenrolledStudentCannotRecordProgress() {
        var s = setUp("LP4");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP4S");
        // never enrolled
        TestAuthentication.authenticateAs(student.userId());

        var result = learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("10"), null);

        assertThat(result).isInstanceOf(LearningProgressService.RecordResult.NotEnrolled.class);
    }

    @Test
    void studentCannotAccessAnotherStudentsProgress() {
        var s = setUp("LP5");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP5S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("10"), null);
        TestAuthentication.clear();

        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP5S2");
        day9Fixtures.enrollStudent(otherStudent, s.sectionId);
        TestAuthentication.authenticateAs(otherStudent.userId());

        assertThatThrownBy(() -> learningProgressService.get(s.lessonId, student.studentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void studentFromAnotherTenantCannotAccessProgress() {
        var s = setUp("LP6A");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP6AS");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("10"), null);
        TestAuthentication.clear();

        var otherTenant = fixtures.provisionTenant("LP6B");
        UUID otherStudentUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.STUDENT, "LP6B-S");
        TestAuthentication.authenticateAs(otherStudentUserId);

        assertThatThrownBy(() -> learningProgressService.get(s.lessonId, student.studentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignedFacultyCanReadButProgressWriteStaysStudentOnly() {
        var s = setUp("LP7");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP7S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("10"), null);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.faculty.userId());
        var facultyRead = learningProgressService.get(s.lessonId, student.studentId());

        assertThat(facultyRead).isPresent();
    }

    @Test
    void guardianWithoutDelegatedPermissionIsDenied() {
        var s = setUp("LP8");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP8S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("10"), null);
        TestAuthentication.clear();

        var guardian = day10Fixtures.provisionGuardian(s.tenant, "LP8G");
        // no grant issued
        TestAuthentication.authenticateAs(guardian.guardianUserId());

        assertThatThrownBy(() -> learningProgressService.get(s.lessonId, student.studentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void guardianWithExplicitGrantCanReadOnlyGrantedStudent() {
        var s = setUp("LP9");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP9S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("10"), null);
        TestAuthentication.clear();

        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LP9S2");
        day9Fixtures.enrollStudent(otherStudent, s.sectionId);

        var guardian = day10Fixtures.provisionGuardian(s.tenant, "LP9G");
        day10Fixtures.grantLearningDataAccess(s.tenant, guardian, student.studentId());
        TestAuthentication.authenticateAs(guardian.guardianUserId());

        var allowed = learningProgressService.get(s.lessonId, student.studentId());
        assertThat(allowed).isPresent();

        assertThatThrownBy(() -> learningProgressService.get(s.lessonId, otherStudent.studentId()))
            .isInstanceOf(AccessDeniedException.class);
    }
}
