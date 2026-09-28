package com.setu.assessment.application;

import com.setu.assessment.domain.AssessmentResponse;
import com.setu.assessment.infrastructure.AssessmentAttemptRepository;
import com.setu.assessment.infrastructure.AssessmentResponseRepository;
import com.setu.assessment.infrastructure.GradeRepository;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import com.setu.testsupport.Day11TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 11 §Concurrency: the six required tests, genuine separate threads
 * (each own transaction/connection) — same discipline as Day 9's {@code
 * AttendanceConcurrencyTests} / Day 10's {@code LearningConcurrencyTests}.
 * Deliberately NOT class-annotated {@code @Transactional} (the REQUIRES_NEW
 * guards need this test's own fixture setup genuinely committed).
 */
@SpringBootTest
class AssessmentConcurrencyTests {

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
    AssessmentAttemptRepository assessmentAttemptRepository;
    @Autowired
    AssessmentResponseService assessmentResponseService;
    @Autowired
    AssessmentResponseRepository assessmentResponseRepository;
    @Autowired
    GradeService gradeService;
    @Autowired
    GradeRepository gradeRepository;

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

    private <T> List<T> runConcurrently(Callable<T> taskA, Callable<T> taskB) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> wrappedA = () -> {
                ready.countDown();
                start.await();
                return taskA.call();
            };
            Callable<T> wrappedB = () -> {
                ready.countDown();
                start.await();
                return taskB.call();
            };
            Future<T> fa = pool.submit(wrappedA);
            Future<T> fb = pool.submit(wrappedB);
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            return List.of(fa.get(15, TimeUnit.SECONDS), fb.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }
    }

    private AssessmentAttemptService.StartResult startAs(UUID userId, UUID assessmentId) {
        TestAuthentication.authenticateAs(userId);
        try {
            return assessmentAttemptService.start(assessmentId);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** TEST 1: two DIFFERENT students concurrently creating an attempt for the SAME assessment. Expected: both succeed, distinct rows. */
    @Test
    void test1_twoDifferentStudentsConcurrentAttemptCreationBothSucceed() throws Exception {
        var s = setUp("QCRACE1");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var studentA = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QCRACE1SA");
        var studentB = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QCRACE1SB");
        day9Fixtures.enrollStudent(studentA, s.sectionId);
        day9Fixtures.enrollStudent(studentB, s.sectionId);

        List<AssessmentAttemptService.StartResult> results = runConcurrently(
            () -> startAs(studentA.userId(), open.assessmentId()),
            () -> startAs(studentB.userId(), open.assessmentId()));

        assertThat(results).allMatch(r -> r instanceof AssessmentAttemptService.StartResult.Success);
        assertThat(assessmentAttemptRepository.findByAssessmentId(open.assessmentId())).hasSize(2);
    }

    /** TEST 2: the SAME student concurrently creating two attempts for the SAME assessment. Expected: exactly one succeeds, never two rows. */
    @Test
    void test2_sameStudentConcurrentAttemptCreationProducesExactlyOne() throws Exception {
        var s = setUp("QCRACE2");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QCRACE2S");
        day9Fixtures.enrollStudent(student, s.sectionId);

        List<AssessmentAttemptService.StartResult> results = runConcurrently(
            () -> startAs(student.userId(), open.assessmentId()),
            () -> startAs(student.userId(), open.assessmentId()));

        long successCount = results.stream().filter(r -> r instanceof AssessmentAttemptService.StartResult.Success).count();
        long conflictCount = results.stream().filter(r -> r instanceof AssessmentAttemptService.StartResult.AlreadyAttempted).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);
        assertThat(assessmentAttemptRepository.findByAssessmentIdAndStudentId(open.assessmentId(), student.studentId())).hasSize(1);
    }

    /** TEST 3: two concurrent responses for the SAME (attempt, question). Expected: no silent lost update — exactly one final answer, no duplicate row. */
    @Test
    void test3_concurrentResponsesForSameQuestionNeverLoseAnUpdate() throws Exception {
        var s = setUp("QCRACE3");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QCRACE3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID attemptId = ((AssessmentAttemptService.StartResult.Success) assessmentAttemptService.start(open.assessmentId())).attemptId();
        TestAuthentication.clear();

        List<AssessmentResponseService.RecordResult> results = runConcurrently(
            () -> {
                TestAuthentication.authenticateAs(student.userId());
                try {
                    return assessmentResponseService.recordResponse(attemptId, open.questionId(), "{\"selected\": \"A\"}");
                } finally {
                    TestAuthentication.clear();
                }
            },
            () -> {
                TestAuthentication.authenticateAs(student.userId());
                try {
                    return assessmentResponseService.recordResponse(attemptId, open.questionId(), "{\"selected\": \"B\"}");
                } finally {
                    TestAuthentication.clear();
                }
            });

        long successCount = results.stream().filter(r -> r instanceof AssessmentResponseService.RecordResult.Success).count();
        // Do not claim exactly-once semantics — either both succeed (insert then version-checked update) or the
        // loser gets a typed VersionConflict; either way, the DB row itself must stay singular and coherent.
        assertThat(successCount).isGreaterThanOrEqualTo(1);
        List<AssessmentResponse> rows = assessmentResponseRepository.findByAttemptId(attemptId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).answerJson()).isIn("{\"selected\": \"A\"}", "{\"selected\": \"B\"}");
    }

    /**
     * TEST 4: two concurrent FIRST publications for the SAME (enrollment,
     * component) — nothing exists yet. Expected (see {@code
     * GradeService#publishOrCorrect}'s javadoc): the owning component's
     * lock fully serializes them — both succeed, the second one correctly
     * observed as a correction (version 2) once it acquires the lock and
     * reads the first one's now-committed row, nothing lost, no silent
     * duplicate version 1.
     */
    @Test
    void test4_concurrentFirstGradePublicationBothSucceedSequentially() throws Exception {
        var s = setUp("QCRACE4");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QCRACE4S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);

        List<GradeService.PublishResult> results = runConcurrently(
            () -> publishAs(s.faculty.userId(), open, student.studentId(), new BigDecimal("8"), "B"),
            () -> publishAs(s.faculty.userId(), open, student.studentId(), new BigDecimal("9"), "A"));

        assertThat(results).allMatch(r -> r instanceof GradeService.PublishResult.Success);
        var versions = results.stream().map(r -> ((GradeService.PublishResult.Success) r).version()).sorted().toList();
        assertThat(versions).isEqualTo(List.of(1, 2));
        assertThat(gradeRepository.findByEnrollmentIdAndComponentIdOrderByVersionDesc(enrollmentId, open.componentId())).hasSize(2);
        assertThat(gradeRepository.findByEnrollmentIdAndIsCurrentTrue(enrollmentId)).hasSize(1);
    }

    /** TEST 5: two concurrent CORRECTIONS of an already-published grade. Expected (transaction-concurrency-matrix.md scenario 6): the row lock serializes them into sequential versions 2 and 3 — both succeed, nothing lost. */
    @Test
    void test5_concurrentGradeCorrectionsBothSucceedSequentially() throws Exception {
        var s = setUp("QCRACE5");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QCRACE5S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), student.studentId(), new BigDecimal("5"), "C", null);
        TestAuthentication.clear();

        List<GradeService.PublishResult> results = runConcurrently(
            () -> publishAs(s.faculty.userId(), open, student.studentId(), new BigDecimal("7"), "B"),
            () -> publishAs(s.faculty.userId(), open, student.studentId(), new BigDecimal("8"), "B+"));

        assertThat(results).allMatch(r -> r instanceof GradeService.PublishResult.Success);
        var versions = results.stream().map(r -> ((GradeService.PublishResult.Success) r).version()).sorted().toList();
        assertThat(versions).isEqualTo(List.of(2, 3));
        TestAuthentication.authenticateAs(s.faculty.userId());
        var history = gradeService.history(enrollmentId, open.componentId());
        TestAuthentication.clear();
        assertThat(history).hasSize(3);
        assertThat(history.stream().filter(g -> g.version() == 3).findFirst().orElseThrow().rawMarks())
            .isIn(new BigDecimal("7.00"), new BigDecimal("8.00"), new BigDecimal("7"), new BigDecimal("8"));
    }

    /**
     * TEST 6: one caller publishes the FIRST grade for a pair while a
     * second, genuinely concurrent caller races them on the SAME entry
     * point for the SAME pair, one submitting what starts as a correction
     * attempt against a row that may not exist yet from its own point of
     * view. Expected: the component lock resolves the ambiguity
     * deterministically — exactly one {@code is_current} row, versions
     * sequential starting at 1, no duplicate version, both calls succeed
     * (one as the original publish, one as the correction that follows
     * it) — not claiming which thread wins, only that nothing corrupts.
     */
    @Test
    void test6_publicationRacingWithCorrectionNeverCorrupts() throws Exception {
        var s = setUp("QCRACE6");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QCRACE6S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);

        List<GradeService.PublishResult> results = runConcurrently(
            () -> publishAs(s.faculty.userId(), open, student.studentId(), new BigDecimal("6"), "C"),
            () -> publishAs(s.faculty.userId(), open, student.studentId(), new BigDecimal("7"), "B"));

        assertThat(results).allMatch(r -> r instanceof GradeService.PublishResult.Success);

        var currentRows = gradeRepository.findByEnrollmentIdAndIsCurrentTrue(enrollmentId);
        assertThat(currentRows).hasSize(1);
        TestAuthentication.authenticateAs(s.faculty.userId());
        var history = gradeService.history(enrollmentId, open.componentId());
        TestAuthentication.clear();
        var versions = history.stream().map(g -> g.version()).sorted().toList();
        assertThat(versions).isEqualTo(List.of(1, 2));
    }

    private GradeService.PublishResult publishAs(UUID facultyUserId, Day11TestFixtures.OpenAssessment open, UUID studentId,
                                                   BigDecimal rawMarks, String gradeCode) {
        TestAuthentication.authenticateAs(facultyUserId);
        try {
            return gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), studentId, rawMarks, gradeCode, null);
        } finally {
            TestAuthentication.clear();
        }
    }
}
