package com.setu.learning.application;

import com.setu.identity.TestAuthentication;
import com.setu.learning.domain.AssignmentSubmissionStatus;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import com.setu.testsupport.Day10TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 10 §6, §14: assignment_submissions — the authoritative submit flow,
 * late policy, and server-computed attempt numbers. Deliberately NOT
 * {@code @Transactional} — {@code submit()} uses {@code
 * AssignmentSubmissionInsertGuard}'s {@code PROPAGATION_REQUIRES_NEW}
 * insert (same Day 9 lesson as {@link LearningProgressTests}).
 */
@SpringBootTest
class AssignmentSubmissionTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    AssignmentService assignmentService;
    @Autowired
    AssignmentSubmissionService assignmentSubmissionService;

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
    void enrolledStudentCanSubmitToPublishedAssignment() {
        var s = setUp("SB1");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1",
            Instant.now().plus(7, ChronoUnit.DAYS), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB1S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = assignmentSubmissionService.submit(assignmentId, "s3://submissions/1.pdf");

        assertThat(result).isInstanceOf(AssignmentSubmissionService.SubmitResult.Success.class);
        var success = (AssignmentSubmissionService.SubmitResult.Success) result;
        assertThat(success.status()).isEqualTo(AssignmentSubmissionStatus.SUBMITTED);
        assertThat(success.attemptNo()).isEqualTo(1);
    }

    @Test
    void unenrolledStudentCannotSubmit() {
        var s = setUp("SB2");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1",
            null, null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB2S");
        // never enrolled
        TestAuthentication.authenticateAs(student.userId());

        var result = assignmentSubmissionService.submit(assignmentId, "s3://x");

        assertThat(result).isInstanceOf(AssignmentSubmissionService.SubmitResult.NotEnrolled.class);
    }

    @Test
    void cannotSubmitToDraftAssignment() {
        var s = setUp("SB3");
        TestAuthentication.authenticateAs(s.faculty.userId());
        var created = (AssignmentService.CreateResult.Success)
            assignmentService.create(s.sectionId, "HW1", new java.math.BigDecimal("100"), null, null);
        TestAuthentication.clear();
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = assignmentSubmissionService.submit(created.assignmentId(), "s3://x");

        assertThat(result).isInstanceOf(AssignmentSubmissionService.SubmitResult.AssignmentNotAvailable.class);
    }

    @Test
    void cannotSubmitToClosedAssignment() {
        var s = setUp("SB4");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1", null, null);
        TestAuthentication.authenticateAs(s.faculty.userId());
        assignmentService.close(assignmentId);
        TestAuthentication.clear();
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB4S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = assignmentSubmissionService.submit(assignmentId, "s3://x");

        assertThat(result).isInstanceOf(AssignmentSubmissionService.SubmitResult.AssignmentNotAvailable.class);
    }

    @Test
    void lateSubmissionAcceptedWhenAllowLateTrue() {
        var s = setUp("SB5");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1",
            Instant.now().minus(1, ChronoUnit.DAYS), "{\"allowLate\": true}");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB5S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = assignmentSubmissionService.submit(assignmentId, "s3://x");

        assertThat(result).isInstanceOf(AssignmentSubmissionService.SubmitResult.Success.class);
        assertThat(((AssignmentSubmissionService.SubmitResult.Success) result).status()).isEqualTo(AssignmentSubmissionStatus.LATE);
    }

    @Test
    void lateSubmissionRejectedWhenAllowLateFalse() {
        var s = setUp("SB6");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1",
            Instant.now().minus(1, ChronoUnit.DAYS), "{\"allowLate\": false}");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB6S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = assignmentSubmissionService.submit(assignmentId, "s3://x");

        assertThat(result).isInstanceOf(AssignmentSubmissionService.SubmitResult.DeadlinePassed.class);
    }

    @Test
    void onTimeSubmissionSucceedsRegardlessOfLatePolicy() {
        var s = setUp("SB7");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1",
            Instant.now().plus(1, ChronoUnit.DAYS), "{\"allowLate\": false}");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB7S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = assignmentSubmissionService.submit(assignmentId, "s3://x");

        assertThat(result).isInstanceOf(AssignmentSubmissionService.SubmitResult.Success.class);
        assertThat(((AssignmentSubmissionService.SubmitResult.Success) result).status()).isEqualTo(AssignmentSubmissionStatus.SUBMITTED);
    }

    @Test
    void duplicateAttemptIsRejected() {
        var s = setUp("SB8");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1", null, null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB8S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        assignmentSubmissionService.submit(assignmentId, "s3://x");

        var result = assignmentSubmissionService.submit(assignmentId, "s3://x-again");

        assertThat(result).isInstanceOf(AssignmentSubmissionService.SubmitResult.DuplicateAttempt.class);
    }

    @Test
    void facultyCanListAllSubmissionsStudentSeesOnlyOwn() {
        var s = setUp("SB9");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1", null, null);
        var studentA = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB9SA");
        day9Fixtures.enrollStudent(studentA, s.sectionId);
        var studentB = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SB9SB");
        day9Fixtures.enrollStudent(studentB, s.sectionId);

        TestAuthentication.authenticateAs(studentA.userId());
        assignmentSubmissionService.submit(assignmentId, "s3://a");
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(studentB.userId());
        assignmentSubmissionService.submit(assignmentId, "s3://b");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.faculty.userId());
        var facultyView = assignmentSubmissionService.listForAssignment(assignmentId);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(studentA.userId());
        var studentView = assignmentSubmissionService.listForAssignment(assignmentId);

        assertThat(facultyView).hasSize(2);
        assertThat(studentView).hasSize(1);
        assertThat(studentView.get(0).studentId()).isEqualTo(studentA.studentId());
    }
}
