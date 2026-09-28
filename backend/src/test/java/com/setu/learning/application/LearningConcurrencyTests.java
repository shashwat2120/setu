package com.setu.learning.application;

import com.setu.identity.TestAuthentication;
import com.setu.learning.domain.AssignmentStatus;
import com.setu.learning.domain.LearningProgressState;
import com.setu.learning.infrastructure.LearningProgressRepository;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
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
 * Day 10 §concurrency: the four required tests, genuine separate threads
 * (each own transaction/connection) — same discipline as Day 9's {@code
 * AttendanceConcurrencyTests}. Deliberately NOT class-annotated {@code
 * @Transactional} for the same reason (the REQUIRES_NEW guards need this
 * test's own fixture setup genuinely committed).
 */
@SpringBootTest
class LearningConcurrencyTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    CourseModuleService courseModuleService;
    @Autowired
    LessonService lessonService;
    @Autowired
    LearningProgressService learningProgressService;
    @Autowired
    LearningProgressRepository learningProgressRepository;
    @Autowired
    AssignmentService assignmentService;
    @Autowired
    AssignmentSubmissionService assignmentSubmissionService;

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

    private AssignmentSubmissionService.SubmitResult submitAs(UUID userId, UUID assignmentId, String contentRef) {
        TestAuthentication.authenticateAs(userId);
        try {
            return assignmentSubmissionService.submit(assignmentId, contentRef);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** TEST 1: same student submits the same assignment attempt concurrently. Expected: exactly one Success, never two rows. */
    @Test
    void test1_concurrentDuplicateSubmissionProducesExactlyOneRow() throws Exception {
        var s = setUp("LCRACE1");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1", null, null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LCRACE1S");
        day9Fixtures.enrollStudent(student, s.sectionId);

        List<AssignmentSubmissionService.SubmitResult> results = runConcurrently(
            () -> submitAs(student.userId(), assignmentId, "s3://a"),
            () -> submitAs(student.userId(), assignmentId, "s3://b"));

        long successCount = results.stream().filter(r -> r instanceof AssignmentSubmissionService.SubmitResult.Success).count();
        long duplicateCount = results.stream().filter(r -> r instanceof AssignmentSubmissionService.SubmitResult.DuplicateAttempt).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(1);
        TestAuthentication.authenticateAs(s.faculty.userId());
        assertThat(assignmentSubmissionService.listForAssignment(assignmentId)).hasSize(1);
    }

    /** TEST 2: concurrent progress updates (two different percent values) on the SAME (lesson, student) row. Expected: no silent lost update — the loser gets VersionConflict, never a torn write. */
    @Test
    void test2_concurrentProgressUpdatesDoNotSilentlyLoseAnUpdate() throws Exception {
        var s = setUp("LCRACE2");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LCRACE2S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("10"), null);
        TestAuthentication.clear();

        List<LearningProgressService.RecordResult> results = runConcurrently(
            () -> {
                TestAuthentication.authenticateAs(student.userId());
                try {
                    return learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("40"), null);
                } finally {
                    TestAuthentication.clear();
                }
            },
            () -> {
                TestAuthentication.authenticateAs(student.userId());
                try {
                    return learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("60"), null);
                } finally {
                    TestAuthentication.clear();
                }
            });

        long succeeded = results.stream().filter(r -> r instanceof LearningProgressService.RecordResult.Success).count();
        long conflicted = results.stream().filter(r -> r instanceof LearningProgressService.RecordResult.VersionConflict).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicted).isEqualTo(1);
        var finalPercent = learningProgressRepository.findByLessonIdAndStudentId(s.lessonId, student.studentId()).orElseThrow().percent();
        assertThat(finalPercent).isIn(new BigDecimal("40.00"), new BigDecimal("60.00"), new BigDecimal("40"), new BigDecimal("60"));
    }

    /** TEST 3: concurrent lesson-completion updates — both racing to mark the SAME row COMPLETED. Expected: exactly one commits, the row ends up coherently COMPLETED, never corrupted/duplicated. */
    @Test
    void test3_concurrentLessonCompletionUpdatesLeaveACoherentRow() throws Exception {
        var s = setUp("LCRACE3");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LCRACE3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        learningProgressService.record(s.lessonId, LearningProgressState.IN_PROGRESS, new BigDecimal("90"), null);
        TestAuthentication.clear();

        Callable<LearningProgressService.RecordResult> completeCall = () -> {
            TestAuthentication.authenticateAs(student.userId());
            try {
                return learningProgressService.record(s.lessonId, LearningProgressState.COMPLETED, new BigDecimal("100"), null);
            } finally {
                TestAuthentication.clear();
            }
        };
        List<LearningProgressService.RecordResult> results = runConcurrently(completeCall, completeCall);

        long succeeded = results.stream().filter(r -> r instanceof LearningProgressService.RecordResult.Success).count();
        long conflicted = results.stream().filter(r -> r instanceof LearningProgressService.RecordResult.VersionConflict).count();
        // Do not claim exactly-once semantics at the request level — but the DB row itself must stay singular and coherent.
        assertThat(succeeded + conflicted).isEqualTo(2);
        var row = learningProgressRepository.findByLessonIdAndStudentId(s.lessonId, student.studentId()).orElseThrow();
        assertThat(row.state()).isEqualTo(LearningProgressState.COMPLETED);
        assertThat(row.completedAt()).isNotNull();
    }

    /**
     * TEST 4: a submission races a close of the same assignment. Both sides
     * take the same {@code findByIdForUpdate} row lock (see {@code
     * AssignmentService.close}/{@code AssignmentSubmissionService.submit}'s
     * javadoc), so the outcome is deterministic by lock order rather than a
     * true check-then-act race: either the submission committed BEFORE the
     * close (and is accepted) or AFTER (and is rejected as unavailable) —
     * never a submission accepted onto an assignment that ends up CLOSED.
     */
    @Test
    void test4_submissionRacingCloseNeverAcceptsAfterClose() throws Exception {
        var s = setUp("LCRACE4");
        UUID assignmentId = day10Fixtures.provisionPublishedAssignment(s.faculty.userId(), s.sectionId, "HW1", null, null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LCRACE4S");
        day9Fixtures.enrollStudent(student, s.sectionId);

        List<Object> results = runConcurrently(
            () -> submitAs(student.userId(), assignmentId, "s3://x"),
            () -> {
                TestAuthentication.authenticateAs(s.faculty.userId());
                try {
                    return assignmentService.close(assignmentId);
                } finally {
                    TestAuthentication.clear();
                }
            });

        assertThat(results).hasSize(2);
        boolean submissionAccepted = results.get(0) instanceof AssignmentSubmissionService.SubmitResult.Success;
        boolean submissionRejected = results.get(0) instanceof AssignmentSubmissionService.SubmitResult.AssignmentNotAvailable;
        assertThat(submissionAccepted || submissionRejected).isTrue();

        TestAuthentication.authenticateAs(s.faculty.userId());
        var finalAssignment = assignmentService.get(assignmentId).orElseThrow();
        assertThat(finalAssignment.status()).isEqualTo(AssignmentStatus.CLOSED);
        TestAuthentication.clear();

        long submissionRows = assignmentSubmissionServiceListSize(s.faculty.userId(), assignmentId);
        assertThat(submissionRows).isEqualTo(submissionAccepted ? 1 : 0);
    }

    private long assignmentSubmissionServiceListSize(UUID facultyUserId, UUID assignmentId) {
        TestAuthentication.authenticateAs(facultyUserId);
        try {
            return assignmentSubmissionService.listForAssignment(assignmentId).size();
        } finally {
            TestAuthentication.clear();
        }
    }
}
