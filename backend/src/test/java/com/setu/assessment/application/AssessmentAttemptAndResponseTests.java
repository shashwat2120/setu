package com.setu.assessment.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.assessment.domain.AssessmentAttemptStatus;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import com.setu.testsupport.Day11TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 11 §Attempts, §Responses, §Authorization: the student attempt/
 * response flow, including the explicit "student accesses only own
 * attempts/responses" / "cross-tenant object IDs denied" requirements.
 * Deliberately NOT {@code @Transactional} — {@code start()}/{@code
 * recordResponse()} use {@code REQUIRES_NEW} insert guards (Day 9/10
 * precedent).
 */
@SpringBootTest
class AssessmentAttemptAndResponseTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day11TestFixtures day11Fixtures;
    @Autowired
    AssessmentAttemptService assessmentAttemptService;
    @Autowired
    AssessmentResponseService assessmentResponseService;

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
    void enrolledStudentCanStartAttempt() {
        var s = setUp("ATT1");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT1S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = assessmentAttemptService.start(open.assessmentId());

        assertThat(result).isInstanceOf(AssessmentAttemptService.StartResult.Success.class);
    }

    @Test
    void unenrolledStudentCannotStartAttempt() {
        var s = setUp("ATT2");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT2S");
        TestAuthentication.authenticateAs(student.userId());

        var result = assessmentAttemptService.start(open.assessmentId());

        assertThat(result).isInstanceOf(AssessmentAttemptService.StartResult.NotEnrolled.class);
    }

    @Test
    void secondAttemptIsBlocked() {
        var s = setUp("ATT3");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        assessmentAttemptService.start(open.assessmentId());

        var result = assessmentAttemptService.start(open.assessmentId());

        assertThat(result).isInstanceOf(AssessmentAttemptService.StartResult.AlreadyAttempted.class);
    }

    @Test
    void studentCanRecordAndSubmitResponse() {
        var s = setUp("ATT4");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT4S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(open.assessmentId())).attemptId();

        var recordResult = assessmentResponseService.recordResponse(attemptId, open.questionId(), "{\"selected\": \"B\"}");
        var submitResult = assessmentAttemptService.submit(attemptId);

        assertThat(recordResult).isInstanceOf(AssessmentResponseService.RecordResult.Success.class);
        assertThat(submitResult).isInstanceOf(AssessmentAttemptService.SubmitResult.Success.class);
        assertThat(assessmentAttemptService.get(attemptId).orElseThrow().status()).isEqualTo(AssessmentAttemptStatus.SUBMITTED);
    }

    @Test
    void responseIsRejectedAfterSubmission() {
        var s = setUp("ATT5");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT5S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(open.assessmentId())).attemptId();
        assessmentAttemptService.submit(attemptId);

        var result = assessmentResponseService.recordResponse(attemptId, open.questionId(), "{\"selected\": \"B\"}");

        assertThat(result).isInstanceOf(AssessmentResponseService.RecordResult.AttemptNotInProgress.class);
    }

    @Test
    void studentCanChangeAnswerBeforeSubmitting() {
        var s = setUp("ATT6");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT6S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(open.assessmentId())).attemptId();
        assessmentResponseService.recordResponse(attemptId, open.questionId(), "{\"selected\": \"A\"}");

        var result = assessmentResponseService.recordResponse(attemptId, open.questionId(), "{\"selected\": \"B\"}");

        assertThat(result).isInstanceOf(AssessmentResponseService.RecordResult.Success.class);
        assertThat(assessmentResponseService.listForAttempt(attemptId)).hasSize(1);
    }

    @Test
    void studentCannotAccessAnotherStudentsAttempt() {
        var s = setUp("ATT7");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT7S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(open.assessmentId())).attemptId();
        TestAuthentication.clear();

        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT7S2");
        day9Fixtures.enrollStudent(otherStudent, s.sectionId);
        TestAuthentication.authenticateAs(otherStudent.userId());

        assertThatThrownBy(() -> assessmentAttemptService.get(attemptId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void facultyFromAnotherTenantCannotAccessAttempt() {
        var s = setUp("ATT8A");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT8AS");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(open.assessmentId())).attemptId();
        TestAuthentication.clear();

        var otherTenant = fixtures.provisionTenant("ATT8B");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.FACULTY, "ATT8B-F");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> assessmentAttemptService.get(attemptId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignedFacultyCanReadStudentAttempt() {
        var s = setUp("ATT9");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT9S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(open.assessmentId())).attemptId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.faculty.userId());
        var result = assessmentAttemptService.get(attemptId);

        assertThat(result).isPresent();
    }

    @Test
    void invalidAttemptIdReturnsEmptyOrDenied() {
        var s = setUp("ATT10");
        TestAuthentication.authenticateAs(s.faculty.userId());

        assertThat(assessmentAttemptService.get(UUID.randomUUID())).isEmpty();
    }

    @Test
    void questionMustBelongToAttemptsAssessment() {
        var s = setUp("ATT11A");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var s2 = setUp("ATT11B");
        var other = day11Fixtures.provisionOpenAssessment(s2.faculty.userId(), s2.sectionId, "Quiz2");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT11S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(open.assessmentId())).attemptId();

        var result = assessmentResponseService.recordResponse(attemptId, other.questionId(), "{\"selected\": \"B\"}");

        assertThat(result).isInstanceOf(AssessmentResponseService.RecordResult.QuestionNotInAssessment.class);
    }

    // --- Day 16 §student: listForAssessment self-view ---

    @Test
    void enrolledStudentCanListTheirOwnAttempt() {
        var s = setUp("ATT12");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT12S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        assessmentAttemptService.start(open.assessmentId());

        var attempts = assessmentAttemptService.listForAssessment(open.assessmentId());

        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).studentId()).isEqualTo(student.studentId());
    }

    @Test
    void studentListingDoesNotLeakAnotherStudentsAttempt() {
        var s = setUp("ATT13");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var studentA = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT13SA");
        var studentB = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT13SB");
        day9Fixtures.enrollStudent(studentA, s.sectionId);
        day9Fixtures.enrollStudent(studentB, s.sectionId);
        TestAuthentication.authenticateAs(studentA.userId());
        assessmentAttemptService.start(open.assessmentId());
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(studentB.userId());
        var attemptsForB = assessmentAttemptService.listForAssessment(open.assessmentId());

        assertThat(attemptsForB).isEmpty();
    }

    @Test
    void assignedFacultyCanStillListAllAttemptsForTheirSection() {
        var s = setUp("ATT14");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ATT14S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        assessmentAttemptService.start(open.assessmentId());
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.faculty.userId());
        var attempts = assessmentAttemptService.listForAssessment(open.assessmentId());

        assertThat(attempts).hasSize(1);
    }
}
