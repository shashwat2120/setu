package com.setu.enrollment.application;

import com.setu.enrollment.domain.EnrollmentStatus;
import com.setu.enrollment.infrastructure.EnrollmentRepository;
import com.setu.enrollment.infrastructure.WaitlistEntryRepository;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
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
 * Day 8 §18: the four required concurrency tests, run with GENUINE separate
 * threads (each authenticating and calling the real {@code @Transactional}
 * service methods, which each open their own transaction/connection) — not
 * mocks, not sequential calls dressed up as "concurrent." Deliberately NOT
 * class-annotated {@code @Transactional} (same reasoning as
 * {@code catalog.Day7ConcurrencyTests}): the whole point is genuinely
 * separate, independently-committed transactions racing each other.
 * <p>
 * Threads are synchronized with a {@link CountDownLatch} so both requests
 * start as close to simultaneously as possible, maximizing the chance they
 * actually contend for the same lock rather than trivially serializing —
 * though correctness here does NOT depend on winning that race; it depends
 * on {@code SELECT ... FOR UPDATE} making the outcome safe regardless of
 * which thread the OS/JDBC scheduler happens to run first.
 */
@SpringBootTest
class EnrollmentConcurrencyTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    EnrollmentRepository enrollmentRepository;
    @Autowired
    WaitlistEntryRepository waitlistEntryRepository;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId) {
    }

    private Scenario setUp(String prefix, int capacity) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", capacity, null);
        return new Scenario(tenant, programId, curriculumId, sectionId);
    }

    /** TEST 1: two students race for the last (one) seat. Expected: exactly one ENROLLED, the other WAITLISTED — never both enrolled, never both waitlisted, never an error. */
    @Test
    void test1_lastSeatRaceProducesExactlyOneEnrolledAndOneWaitlisted() throws Exception {
        var s = setUp("RACE1", 1);
        var studentA = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "RACE1SA");
        var studentB = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "RACE1SB");

        List<EnrollmentService.RequestResult> results = runConcurrently(
            () -> requestAs(studentA.userId(), studentA.studentId(), s.sectionId()),
            () -> requestAs(studentB.userId(), studentB.studentId(), s.sectionId()));

        long enrolledCount = results.stream().filter(r -> r instanceof EnrollmentService.RequestResult.Enrolled).count();
        long waitlistedCount = results.stream().filter(r -> r instanceof EnrollmentService.RequestResult.Waitlisted).count();
        assertThat(enrolledCount).isEqualTo(1);
        assertThat(waitlistedCount).isEqualTo(1);
        assertThat(enrollmentRepository.countBySectionIdAndStatus(s.sectionId(), EnrollmentStatus.ENROLLED)).isEqualTo(1);
    }

    /** TEST 2: the SAME student concurrently submits two enrollment requests for the same section. Expected: exactly one authoritative (non-duplicate) outcome; the section's FOR UPDATE lock serializes the pair, so the second sees the first's committed row and is cleanly rejected as a duplicate — never a DB constraint exception leaking out, never two active rows. */
    @Test
    void test2_sameStudentConcurrentDoubleEnrollProducesExactlyOneAuthoritativeEnrollment() throws Exception {
        var s = setUp("RACE2", 5);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "RACE2S1");

        List<EnrollmentService.RequestResult> results = runConcurrently(
            () -> requestAs(student.userId(), student.studentId(), s.sectionId()),
            () -> requestAs(student.userId(), student.studentId(), s.sectionId()));

        long enrolledCount = results.stream().filter(r -> r instanceof EnrollmentService.RequestResult.Enrolled).count();
        long duplicateCount = results.stream().filter(r -> r instanceof EnrollmentService.RequestResult.DuplicateActiveEnrollment).count();
        assertThat(enrolledCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(1);
        assertThat(enrollmentRepository.countBySectionIdAndStatus(s.sectionId(), EnrollmentStatus.ENROLLED)).isEqualTo(1);
    }

    /**
     * TEST 3: one thread enrolls a student (capacity=1) while another
     * concurrently raises that section's capacity via
     * {@code CourseSectionService.update}. Documented safe outcome (see
     * {@code EnrollmentService}'s javadoc): the section's {@code FOR UPDATE}
     * lock is a real row lock, so the plain capacity UPDATE necessarily
     * blocks behind it (Postgres row locking applies to any writer, not
     * just other {@code FOR UPDATE} readers) — no lost update, no
     * optimistic-lock exception, no capacity overflow. This test asserts
     * the outcome (both operations succeed, final capacity is exactly what
     * the admin set, exactly one seat consumed), not the precise
     * interleaving order, which the database's lock scheduler controls.
     */
    @Test
    void test3_concurrentEnrollmentAndCapacityUpdateBothSucceedWithNoLostUpdate() throws Exception {
        var s = setUp("RACE3", 1);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "RACE3S1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> enrollTask = () -> {
                TestAuthentication.authenticateAs(student.userId());
                ready.countDown();
                start.await();
                try {
                    return enrollmentService.request(student.studentId(), s.sectionId());
                } finally {
                    TestAuthentication.clear();
                }
            };
            Callable<Object> capacityTask = () -> {
                TestAuthentication.authenticateAs(s.tenant.adminUserId());
                ready.countDown();
                start.await();
                try {
                    return courseSectionService.update(s.sectionId(), "S1", 5, null, null);
                } finally {
                    TestAuthentication.clear();
                }
            };
            Future<Object> f1 = pool.submit(enrollTask);
            Future<Object> f2 = pool.submit(capacityTask);
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            Object enrollResult = f1.get(15, TimeUnit.SECONDS);
            Object capacityResult = f2.get(15, TimeUnit.SECONDS);

            assertThat(enrollResult).isInstanceOf(EnrollmentService.RequestResult.Enrolled.class);
            assertThat(capacityResult).isInstanceOf(CourseSectionService.UpdateResult.Success.class);
            assertThat(courseSectionRepository.findById(s.sectionId()).orElseThrow().capacity()).isEqualTo(5);
            assertThat(enrollmentRepository.countBySectionIdAndStatus(s.sectionId(), EnrollmentStatus.ENROLLED)).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    /** TEST 4: two ENROLLED students drop concurrently while two other students wait on the waitlist. Expected: both drops succeed, each promotes a DIFFERENT waitlisted student (SKIP LOCKED), no seat lost, no double-promotion, no capacity overflow. */
    @Test
    void test4_concurrentDropsEachPromoteADistinctWaitlistedStudent() throws Exception {
        var s = setUp("RACE4", 2);
        var studentA = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "RACE4SA");
        var studentB = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "RACE4SB");
        var studentC = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "RACE4SC");
        var studentD = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "RACE4SD");
        UUID enrollmentA = enrollId(requestAs(studentA.userId(), studentA.studentId(), s.sectionId()));
        UUID enrollmentB = enrollId(requestAs(studentB.userId(), studentB.studentId(), s.sectionId()));
        requestAs(studentC.userId(), studentC.studentId(), s.sectionId()); // WAITLISTED position 1
        requestAs(studentD.userId(), studentD.studentId(), s.sectionId()); // WAITLISTED position 2
        assertThat(enrollmentRepository.countBySectionIdAndStatus(s.sectionId(), EnrollmentStatus.ENROLLED)).isEqualTo(2);

        List<EnrollmentService.DropResult> dropResults = runConcurrently(
            () -> dropAs(studentA.userId(), enrollmentA),
            () -> dropAs(studentB.userId(), enrollmentB));

        assertThat(dropResults).allMatch(r -> r instanceof EnrollmentService.DropResult.Success success && success.promotedWaitlist());
        assertThat(enrollmentRepository.countBySectionIdAndStatus(s.sectionId(), EnrollmentStatus.ENROLLED)).isEqualTo(2);
        // The two promoted enrollments must be C and D — a different student each, no seat lost, no overflow.
        boolean cEnrolled = enrollmentRepository.findBySectionIdAndStudentId(s.sectionId(), studentC.studentId())
            .map(e -> e.status() == EnrollmentStatus.ENROLLED).orElse(false);
        boolean dEnrolled = enrollmentRepository.findBySectionIdAndStudentId(s.sectionId(), studentD.studentId())
            .map(e -> e.status() == EnrollmentStatus.ENROLLED).orElse(false);
        assertThat(cEnrolled).isTrue();
        assertThat(dEnrolled).isTrue();
    }

    // --- helpers ---

    @Autowired
    private com.setu.enrollment.application.CourseSectionService courseSectionService;
    @Autowired
    private com.setu.enrollment.infrastructure.CourseSectionRepository courseSectionRepository;

    private EnrollmentService.RequestResult requestAs(UUID userId, UUID studentId, UUID sectionId) {
        TestAuthentication.authenticateAs(userId);
        try {
            return enrollmentService.request(studentId, sectionId);
        } finally {
            TestAuthentication.clear();
        }
    }

    private EnrollmentService.DropResult dropAs(UUID userId, UUID enrollmentId) {
        TestAuthentication.authenticateAs(userId);
        try {
            return enrollmentService.drop(enrollmentId, "concurrency test drop");
        } finally {
            TestAuthentication.clear();
        }
    }

    private UUID enrollId(EnrollmentService.RequestResult result) {
        return ((EnrollmentService.RequestResult.Enrolled) result).enrollmentId();
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
            T resultA = fa.get(15, TimeUnit.SECONDS);
            T resultB = fb.get(15, TimeUnit.SECONDS);
            return List.of(resultA, resultB);
        } finally {
            pool.shutdown();
        }
    }
}
