package com.setu.attendance.application;

import com.setu.attendance.domain.AttendanceRecordStatus;
import com.setu.attendance.infrastructure.AttendanceRecordRepository;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * Day 9 §14: the four required concurrency tests, with genuine separate
 * threads (each own transaction/connection) — same discipline as
 * {@code enrollment.EnrollmentConcurrencyTests}. Deliberately NOT
 * class-annotated {@code @Transactional} for the same reason.
 */
@SpringBootTest
class AttendanceConcurrencyTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    AttendanceSessionService attendanceSessionService;
    @Autowired
    AttendanceRecordService attendanceRecordService;
    @Autowired
    AttendanceRecordRepository attendanceRecordRepository;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId,
                             UUID meetingId, Day9TestFixtures.Faculty faculty) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        UUID meetingId = day9Fixtures.provisionMeetingPattern(tenant, sectionId, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        return new Scenario(tenant, programId, curriculumId, sectionId, meetingId, faculty);
    }

    private record OpenedSession(String rawToken, UUID sessionId) {
    }

    private OpenedSession openSession(Scenario s) {
        TestAuthentication.authenticateAs(s.faculty.userId());
        try {
            var result = (AttendanceSessionService.OpenResult.Success)
                attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));
            return new OpenedSession(result.rawToken(), result.sessionId());
        } finally {
            TestAuthentication.clear();
        }
    }

    private AttendanceRecordService.ScanResult scanAs(UUID userId, UUID sessionId, String token) {
        TestAuthentication.authenticateAs(userId);
        try {
            return attendanceRecordService.scan(sessionId, token);
        } finally {
            TestAuthentication.clear();
        }
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

    /** TEST 1: same student scans the same QR concurrently. Expected: exactly one attendance record, never a leaked DB exception. */
    @Test
    void test1_sameStudentConcurrentDuplicateScanProducesExactlyOneRecord() throws Exception {
        var s = setUp("QRACE1");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QRACE1S");
        day9Fixtures.enrollStudent(student, s.sectionId);

        List<AttendanceRecordService.ScanResult> results = runConcurrently(
            () -> scanAs(student.userId(), session.sessionId(), session.rawToken()),
            () -> scanAs(student.userId(), session.sessionId(), session.rawToken()));

        long successCount = results.stream().filter(r -> r instanceof AttendanceRecordService.ScanResult.Success).count();
        long idempotentCount = results.stream().filter(r -> r instanceof AttendanceRecordService.ScanResult.AlreadyRecorded).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(idempotentCount).isEqualTo(1);
        assertThat(attendanceRecordRepository.findByAttendanceSessionId(session.sessionId())).hasSize(1);
    }

    /** TEST 2: two different students scan the same QR concurrently. Expected: both valid, distinct records. */
    @Test
    void test2_differentStudentsConcurrentScanBothSucceed() throws Exception {
        var s = setUp("QRACE2");
        var session = openSession(s);
        var studentA = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QRACE2SA");
        var studentB = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QRACE2SB");
        day9Fixtures.enrollStudent(studentA, s.sectionId);
        day9Fixtures.enrollStudent(studentB, s.sectionId);

        List<AttendanceRecordService.ScanResult> results = runConcurrently(
            () -> scanAs(studentA.userId(), session.sessionId(), session.rawToken()),
            () -> scanAs(studentB.userId(), session.sessionId(), session.rawToken()));

        assertThat(results).allMatch(r -> r instanceof AttendanceRecordService.ScanResult.Success);
        assertThat(attendanceRecordRepository.findByAttendanceSessionId(session.sessionId())).hasSize(2);
    }

    /**
     * TEST 3: a scan races a session close. Documented safe outcome (see
     * {@code AttendanceRecordService}'s javadoc): whichever transaction
     * commits first wins — either the scan's PRESENT row, or the close's
     * system-generated ABSENT row — the composite unique constraint
     * guarantees exactly one row for that (session, enrollment) pair,
     * never zero, never two, never a partial/corrupt row. This test
     * asserts THAT invariant, not a predetermined winner.
     */
    @Test
    void test3_scanRacingSessionCloseNeverProducesAPartialOrDuplicateRecord() throws Exception {
        var s = setUp("QRACE3");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QRACE3S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);

        List<Object> results = runConcurrently(
            () -> scanAs(student.userId(), session.sessionId(), session.rawToken()),
            () -> {
                TestAuthentication.authenticateAs(s.faculty.userId());
                try {
                    return attendanceSessionService.close(session.sessionId());
                } finally {
                    TestAuthentication.clear();
                }
            });

        // No exception escaped either branch (both futures resolved via runConcurrently's .get(), which would have
        // rethrown otherwise) — and exactly one attendance_records row exists for this student's enrollment,
        // whichever side won the race (PRESENT from the scan, or system-generated ABSENT from the close).
        assertThat(results).hasSize(2);
        long rowsForThisEnrollment = attendanceRecordRepository.findByAttendanceSessionId(session.sessionId()).stream()
            .filter(r -> r.enrollmentId().equals(enrollmentId))
            .count();
        assertThat(rowsForThisEnrollment).isEqualTo(1);
    }

    /** TEST 4: two concurrent corrections of the same record. Expected: no silent lost update — the loser gets an optimistic-lock conflict, never a torn write. */
    @Test
    void test4_concurrentCorrectionsDoNotSilentlyLoseAnUpdate() throws Exception {
        var s = setUp("QRACE4");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "QRACE4S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        UUID recordId = ((AttendanceRecordService.ScanResult.Success)
            scanAs(student.userId(), session.sessionId(), session.rawToken())).recordId();
        TestAuthentication.authenticateAs(s.faculty.userId());
        attendanceSessionService.close(session.sessionId());
        TestAuthentication.clear();

        List<Object> results = runConcurrently(
            () -> {
                TestAuthentication.authenticateAs(s.faculty.userId());
                try {
                    return attendanceRecordService.correct(recordId, AttendanceRecordStatus.LATE, "correction A");
                } finally {
                    TestAuthentication.clear();
                }
            },
            () -> {
                TestAuthentication.authenticateAs(s.faculty.userId());
                try {
                    return attendanceRecordService.correct(recordId, AttendanceRecordStatus.EXCUSED, "correction B");
                } finally {
                    TestAuthentication.clear();
                }
            });

        long succeeded = results.stream().filter(r -> r instanceof AttendanceRecordService.CorrectResult.Success).count();
        long conflicted = results.stream().filter(r -> r instanceof AttendanceRecordService.CorrectResult.VersionConflict).count();
        // Exactly one commit can win at the database level (Hibernate's @Version check) — the other gets a typed
        // VersionConflict (never a raw exception, never a silently overwritten value).
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicted).isEqualTo(1);
        var finalStatus = attendanceRecordRepository.findById(recordId).orElseThrow().status();
        assertThat(finalStatus).isIn(AttendanceRecordStatus.LATE, AttendanceRecordStatus.EXCUSED);
    }
}
