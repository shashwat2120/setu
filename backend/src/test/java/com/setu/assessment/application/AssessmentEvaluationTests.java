package com.setu.assessment.application;

import com.setu.identity.TestAuthentication;
import com.setu.assessment.domain.QuestionType;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import com.setu.testsupport.Day11TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 11 §Evaluation: deterministic evaluation per QuestionType — see
 * AssessmentEvaluationService's javadoc for the exact documented algorithm
 * per type. Deliberately NOT {@code @Transactional} — {@code start()} uses
 * {@code AssessmentAttemptInsertGuard}'s {@code PROPAGATION_REQUIRES_NEW}
 * insert, which opens a genuinely separate connection that cannot see this
 * test's own fixture setup (assessment/component/question) if it were
 * sitting in an uncommitted auto-rollback transaction — the same Day 9/10
 * lesson. Note that an FK violation from that invisibility is itself a
 * {@code DataIntegrityViolationException}, so it was previously
 * misreported as {@code AlreadyAttempted} rather than surfacing as the
 * real cause.
 */
@SpringBootTest
class AssessmentEvaluationTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day11TestFixtures day11Fixtures;
    @Autowired
    AssessmentService assessmentService;
    @Autowired
    AssessmentComponentService assessmentComponentService;
    @Autowired
    QuestionService questionService;
    @Autowired
    AssessmentAttemptService assessmentAttemptService;
    @Autowired
    AssessmentResponseService assessmentResponseService;
    @Autowired
    AssessmentEvaluationService assessmentEvaluationService;

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

    /** Builds a DRAFT assessment with one component and one question of the given type/metadata, opens it, and returns the ids needed to attempt it. */
    private record Ready(UUID assessmentId, UUID questionId) {
    }

    private Ready buildAssessmentWithQuestion(Scenario s, String prefix, QuestionType type, String metadataJson, BigDecimal points) {
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, prefix, null, null);
        TestAuthentication.authenticateAs(s.faculty.userId());
        var component = (AssessmentComponentService.CreateResult.Success)
            assessmentComponentService.create(assessmentId, "C1", points, new BigDecimal("100"), 1);
        var question = (QuestionService.CreateResult.Success)
            questionService.create(component.componentId(), type, "Q", points, null, null, metadataJson);
        assessmentService.schedule(assessmentId);
        assessmentService.open(assessmentId);
        TestAuthentication.clear();
        return new Ready(assessmentId, question.questionId());
    }

    private UUID attemptAndSubmit(Ready ready, UUID studentUserId, String answerJson) {
        TestAuthentication.authenticateAs(studentUserId);
        try {
            UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(ready.assessmentId())).attemptId();
            assessmentResponseService.recordResponse(attemptId, ready.questionId(), answerJson);
            assessmentAttemptService.submit(attemptId);
            return attemptId;
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void mcqSingleCorrectAnswerIsAutoGraded() {
        var s = setUp("EV1");
        var ready = buildAssessmentWithQuestion(s, "EV1", QuestionType.MCQ_SINGLE, "{\"correctOption\": \"B\"}", new BigDecimal("10"));
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "EV1S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        UUID attemptId = attemptAndSubmit(ready, student.userId(), "{\"selected\": \"B\"}");

        TestAuthentication.authenticateAs(s.faculty.userId());
        var result = assessmentEvaluationService.evaluateAttempt(attemptId);

        assertThat(result).isInstanceOf(AssessmentEvaluationService.EvaluateResult.Success.class);
        var success = (AssessmentEvaluationService.EvaluateResult.Success) result;
        assertThat(success.totalScore()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(success.manualPendingCount()).isEqualTo(0);
    }

    @Test
    void mcqSingleWrongAnswerScoresZero() {
        var s = setUp("EV2");
        var ready = buildAssessmentWithQuestion(s, "EV2", QuestionType.MCQ_SINGLE, "{\"correctOption\": \"B\"}", new BigDecimal("10"));
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "EV2S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        UUID attemptId = attemptAndSubmit(ready, student.userId(), "{\"selected\": \"A\"}");

        TestAuthentication.authenticateAs(s.faculty.userId());
        var result = (AssessmentEvaluationService.EvaluateResult.Success) assessmentEvaluationService.evaluateAttempt(attemptId);

        assertThat(result.totalScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void trueFalseIsAutoGraded() {
        var s = setUp("EV3");
        var ready = buildAssessmentWithQuestion(s, "EV3", QuestionType.TRUE_FALSE, "{\"correctOption\": \"true\"}", new BigDecimal("5"));
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "EV3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        UUID attemptId = attemptAndSubmit(ready, student.userId(), "{\"selected\": \"true\"}");

        TestAuthentication.authenticateAs(s.faculty.userId());
        var result = (AssessmentEvaluationService.EvaluateResult.Success) assessmentEvaluationService.evaluateAttempt(attemptId);

        assertThat(result.totalScore()).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    void shortAnswerNormalizedMatchIgnoresCaseAndWhitespace() {
        var s = setUp("EV4");
        var ready = buildAssessmentWithQuestion(s, "EV4", QuestionType.SHORT_ANSWER, "{\"correctAnswer\": \"Paris\"}", new BigDecimal("8"));
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "EV4S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        UUID attemptId = attemptAndSubmit(ready, student.userId(), "{\"text\": \"  paris \"}");

        TestAuthentication.authenticateAs(s.faculty.userId());
        var result = (AssessmentEvaluationService.EvaluateResult.Success) assessmentEvaluationService.evaluateAttempt(attemptId);

        assertThat(result.totalScore()).isEqualByComparingTo(new BigDecimal("8"));
    }

    @Test
    void essayIsNeverAutoGradedAndStaysPending() {
        var s = setUp("EV5");
        var ready = buildAssessmentWithQuestion(s, "EV5", QuestionType.ESSAY, null, new BigDecimal("20"));
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "EV5S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        UUID attemptId = attemptAndSubmit(ready, student.userId(), "{\"text\": \"a long essay answer\"}");

        TestAuthentication.authenticateAs(s.faculty.userId());
        var result = (AssessmentEvaluationService.EvaluateResult.Success) assessmentEvaluationService.evaluateAttempt(attemptId);

        assertThat(result.totalScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.manualPendingCount()).isEqualTo(1);
        var responses = assessmentResponseService.listForAttempt(attemptId);
        assertThat(responses.get(0).isCorrect()).isNull();
        assertThat(responses.get(0).pointsAwarded()).isNull();
    }

    @Test
    void cannotEvaluateAnAttemptThatIsStillInProgress() {
        var s = setUp("EV6");
        var ready = buildAssessmentWithQuestion(s, "EV6", QuestionType.MCQ_SINGLE, "{\"correctOption\": \"B\"}", new BigDecimal("10"));
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "EV6S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(ready.assessmentId())).attemptId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.faculty.userId());
        var result = assessmentEvaluationService.evaluateAttempt(attemptId);

        assertThat(result).isInstanceOf(AssessmentEvaluationService.EvaluateResult.NotSubmitted.class);
    }
}
