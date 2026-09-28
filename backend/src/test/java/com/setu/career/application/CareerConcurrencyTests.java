package com.setu.career.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day12TestFixtures;
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
 * Day 12 §Career concurrency: real PostgreSQL, real threads — never claims
 * exactly-once semantics beyond what's actually proven. Deliberately NOT
 * {@code @Transactional} — {@code ApplicationInsertGuard} uses
 * REQUIRES_NEW (same reasoning as every other concurrency test class in
 * this codebase).
 */
@SpringBootTest
class CareerConcurrencyTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    JobService jobService;
    @Autowired
    ApplicationService applicationService;
    @Autowired
    PlacementRecordService placementRecordService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student, UUID jobId, UUID placementOfficer) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID placementOfficer = day12Fixtures.provisionPlacementOfficer(tenant, prefix + "PO");
        TestAuthentication.authenticateAs(placementOfficer);
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(tenant.tenantId(), "Role", "Employer", null)).jobId();
        TestAuthentication.clear();
        return new Scenario(tenant, student, jobId, placementOfficer);
    }

    private ApplicationService.ApplyResult applyAs(UUID userId, UUID jobId, UUID studentId) {
        TestAuthentication.authenticateAs(userId);
        try {
            return applicationService.apply(jobId, studentId);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** TEST 1: two threads racing to submit the SAME (job, student) application concurrently — the (job_id, student_id) unique constraint, backed by ApplicationInsertGuard's REQUIRES_NEW isolation, must let exactly one succeed and never poison the loser's transaction. */
    @Test
    void concurrentDuplicateApplications_exactlyOneSucceeds() throws Exception {
        var s = setUp("CCR1");

        List<ApplicationService.ApplyResult> results = runConcurrently(
            () -> applyAs(s.student.userId(), s.jobId, s.student.studentId()),
            () -> applyAs(s.student.userId(), s.jobId, s.student.studentId()));

        long successCount = results.stream().filter(r -> r instanceof ApplicationService.ApplyResult.Success).count();
        long duplicateCount = results.stream().filter(r -> r instanceof ApplicationService.ApplyResult.AlreadyApplied).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(1);
    }

    private ApplicationService.TransitionResult transitionAs(UUID userId, Callable<ApplicationService.TransitionResult> action) {
        TestAuthentication.authenticateAs(userId);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** TEST 2: two concurrent lifecycle-transition attempts on the SAME application (shortlist vs. reject) — the status-checked conditional UPDATE must let exactly one win; the loser gets a clean InvalidTransition, never a lost update or corrupted status. */
    @Test
    void concurrentApplicationTransitionRace_exactlyOneWins() throws Exception {
        var s = setUp("CCR2");
        UUID applicationId = ((ApplicationService.ApplyResult.Success)
            applyAs(s.student.userId(), s.jobId, s.student.studentId())).applicationId();

        List<ApplicationService.TransitionResult> results = runConcurrently(
            () -> transitionAs(s.placementOfficer, () -> applicationService.shortlist(applicationId)),
            () -> transitionAs(s.placementOfficer, () -> applicationService.reject(applicationId)));

        long successCount = results.stream().filter(r -> r instanceof ApplicationService.TransitionResult.Success).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(results).anyMatch(r -> r instanceof ApplicationService.TransitionResult.InvalidTransition);
    }

    /** TEST 3: two concurrent placement-record insertions for the SAME student — placement_records has no uniqueness constraint (multiple independently-true placement events are allowed), so both must succeed with no lost data. */
    @Test
    void concurrentPlacementRecording_bothSucceedNoDataLoss() throws Exception {
        var s = setUp("CCR3");

        runConcurrently(
            () -> recordAs(s.placementOfficer, s.student.studentId(), s.jobId, "Employer A"),
            () -> recordAs(s.placementOfficer, s.student.studentId(), s.jobId, "Employer B"));

        TestAuthentication.authenticateAs(s.student.userId());
        var records = placementRecordService.listForStudent(s.student.studentId());
        assertThat(records).hasSize(2);
        assertThat(records).extracting(r -> r.employer()).containsExactlyInAnyOrder("Employer A", "Employer B");
    }

    private PlacementRecordService.RecordResult recordAs(UUID userId, UUID studentId, UUID jobId, String employer) {
        TestAuthentication.authenticateAs(userId);
        try {
            return placementRecordService.record(studentId, jobId, employer, "SDE", null, null);
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
}
