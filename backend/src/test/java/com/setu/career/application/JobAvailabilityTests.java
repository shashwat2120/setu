package com.setu.career.application;

import com.setu.career.domain.Job;
import com.setu.career.domain.JobStatus;
import com.setu.career.domain.JobTerms;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job is closed when its deadline passes and when every opening is filled, not only when an officer closes it: the state a student sees is the state applying will give,
 * selecting past the openings is refused, and two officers selecting for the last opening at once cannot both succeed. Not {@code @Transactional}: applying uses its own transaction.
 */
@SpringBootTest
class JobAvailabilityTests {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");

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
    JobAvailability jobAvailability;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private static Job job(JobStatus status, Instant deadline, Integer openings) {
        Job job = new Job(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Role", "Employer", "d");
        job.applyTerms(new JobTerms(null, null, deadline, null, null, openings, null, null), NOW.minus(1, ChronoUnit.DAYS));
        if (status == JobStatus.CLOSED) {
            org.springframework.test.util.ReflectionTestUtils.setField(job, "status", JobStatus.CLOSED);
        }
        return job;
    }

    @Test
    void anOpenJobWithRoomAndTimeTakesApplications() {
        assertThat(JobAvailability.stateOf(job(JobStatus.OPEN, null, null), 0, NOW).closedReason()).isNull();
        assertThat(JobAvailability.stateOf(job(JobStatus.OPEN, NOW.plusSeconds(1), 3), 2, NOW).open()).isTrue();
    }

    @Test
    void aJobIsClosedAtItsDeadlineToTheInstant() {
        var job = job(JobStatus.OPEN, NOW, null);

        assertThat(JobAvailability.stateOf(job, 0, NOW.minusMillis(1)).open()).isTrue();
        assertThat(JobAvailability.stateOf(job, 0, NOW).closedReason()).isEqualTo(JobAvailability.DEADLINE_PASSED);
        assertThat(JobAvailability.stateOf(job, 0, NOW.plusSeconds(60)).closedReason()).isEqualTo(JobAvailability.DEADLINE_PASSED);
    }

    @Test
    void aJobIsFullWhenTheSelectedReachTheOpeningsAndNeverWhenNoneAreStated() {
        assertThat(JobAvailability.stateOf(job(JobStatus.OPEN, null, 2), 1, NOW).open()).isTrue();
        assertThat(JobAvailability.stateOf(job(JobStatus.OPEN, null, 2), 2, NOW).closedReason()).isEqualTo(JobAvailability.FULL);
        assertThat(JobAvailability.stateOf(job(JobStatus.OPEN, null, null), 500, NOW).open()).isTrue();
    }

    @Test
    void anOfficersCloseComesFirstAndTheSelectedCountIsAlwaysReported() {
        var closed = JobAvailability.stateOf(job(JobStatus.CLOSED, NOW.minusSeconds(5), 1), 1, NOW);

        assertThat(closed.closedReason()).isEqualTo(JobAvailability.CLOSED);
        assertThat(closed.selected()).isEqualTo(1);
    }

    private record Setup(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student first, Day8TestFixtures.Student second, UUID officer, UUID jobId, UUID firstApplication,
                         UUID secondApplication) {
    }

    private Setup setUp(String prefix, Integer openings) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var first = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "A");
        var second = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "B");
        UUID officer = day12Fixtures.provisionPlacementOfficer(tenant, prefix + "PO");
        TestAuthentication.authenticateAs(officer);
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(tenant.tenantId(), "Analyst", "Example Co", "d",
            new JobTerms(null, null, null, null, null, openings, null, null))).jobId();
        TestAuthentication.clear();
        UUID a1 = applyAs(first, jobId);
        UUID a2 = applyAs(second, jobId);
        // an application is selected from the shortlist, so both applicants are shortlisted first
        TestAuthentication.authenticateAs(officer);
        applicationService.shortlist(a1);
        applicationService.shortlist(a2);
        TestAuthentication.clear();
        return new Setup(tenant, first, second, officer, jobId, a1, a2);
    }

    private UUID applyAs(Day8TestFixtures.Student student, UUID jobId) {
        TestAuthentication.authenticateAs(student.userId());
        try {
            return ((ApplicationService.ApplyResult.Success) applicationService.apply(jobId, student.studentId())).applicationId();
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void selectingPastTheOpeningsIsRefusedAndTheJobThenTakesNoNewApplications() {
        var s = setUp("JA1", 1);
        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.select(s.firstApplication)).isInstanceOf(ApplicationService.TransitionResult.Success.class);
        var refused = applicationService.select(s.secondApplication);

        assertThat(refused).isEqualTo(new ApplicationService.TransitionResult.OpeningsFilled(1));
        assertThat(jdbc.queryForObject("SELECT status FROM career.applications WHERE id = ?", String.class, s.secondApplication)).isEqualTo("SHORTLISTED");
        var job = jobService.findById(s.jobId).orElseThrow();
        assertThat(jobAvailability.forJob(job, Instant.now())).isEqualTo(new JobAvailability.State(1, JobAvailability.FULL));
        // a third student can no longer apply to a job whose openings are filled
        var tenant = s.tenant;
        TestAuthentication.clear();
        var late = day8Fixtures.provisionStudentWithActiveProgram(tenant,
            jdbc.queryForObject("SELECT program_id FROM enrollment.student_programs WHERE student_id = ?", UUID.class, s.first.studentId()),
            jdbc.queryForObject("SELECT curriculum_id FROM enrollment.student_programs WHERE student_id = ?", UUID.class, s.first.studentId()), "JA1C");
        TestAuthentication.authenticateAs(late.userId());
        assertThat(applicationService.apply(s.jobId, late.studentId())).isInstanceOf(ApplicationService.ApplyResult.JobFull.class);
    }

    @Test
    void aRejectedOrWithdrawnApplicantDoesNotUseUpAnOpening() {
        var s = setUp("JA2", 1);
        TestAuthentication.authenticateAs(s.officer);
        applicationService.reject(s.firstApplication, "Not a fit");

        assertThat(applicationService.select(s.secondApplication)).isInstanceOf(ApplicationService.TransitionResult.Success.class);
    }

    @Test
    void aJobWithNoStatedOpeningsAcceptsAnyNumberOfSelections() {
        var s = setUp("JA3", null);
        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.select(s.firstApplication)).isInstanceOf(ApplicationService.TransitionResult.Success.class);
        assertThat(applicationService.select(s.secondApplication)).isInstanceOf(ApplicationService.TransitionResult.Success.class);
    }

    @Test
    void twoOfficersSelectingForTheLastOpeningAtOnceCannotBothSucceed() throws Exception {
        var s = setUp("JA4", 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<ApplicationService.TransitionResult>> tasks = List.of(
                () -> selectAfter(go, s.officer, s.firstApplication), () -> selectAfter(go, s.officer, s.secondApplication));
            List<Future<ApplicationService.TransitionResult>> futures = tasks.stream().map(pool::submit).toList();
            go.countDown();
            long successes = 0;
            long refused = 0;
            for (var f : futures) {
                var r = f.get();
                if (r instanceof ApplicationService.TransitionResult.Success) {
                    successes++;
                } else if (r instanceof ApplicationService.TransitionResult.OpeningsFilled) {
                    refused++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(refused).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM career.applications WHERE job_id = ? AND status = 'SELECTED'", Long.class, s.jobId)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    private ApplicationService.TransitionResult selectAfter(CountDownLatch go, UUID officer, UUID applicationId) throws InterruptedException {
        TestAuthentication.authenticateAs(officer);
        try {
            go.await();
            return applicationService.select(applicationId);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void aJobPastItsDeadlineIsReportedClosedAndRefusesApplicationsWithTheReason() {
        var s = setUp("JA5", null);
        jdbc.update("UPDATE career.jobs SET application_deadline = now() - interval '1 minute' WHERE id = ?", s.jobId);
        var job = jobService.findById(s.jobId).orElseThrow();

        assertThat(jobAvailability.forJob(job, Instant.now()).closedReason()).isEqualTo(JobAvailability.DEADLINE_PASSED);
        var late = day8Fixtures.provisionStudentWithActiveProgram(s.tenant,
            jdbc.queryForObject("SELECT program_id FROM enrollment.student_programs WHERE student_id = ?", UUID.class, s.first.studentId()),
            jdbc.queryForObject("SELECT curriculum_id FROM enrollment.student_programs WHERE student_id = ?", UUID.class, s.first.studentId()), "JA5C");
        TestAuthentication.authenticateAs(late.userId());
        var result = applicationService.apply(s.jobId, late.studentId());
        assertThat(result).isInstanceOf(ApplicationService.ApplyResult.NotEligible.class);
        assertThat(((ApplicationService.ApplyResult.NotEligible) result).unmetRules()).extracting(ApplicationService.UnmetRule::code).contains(EligibilityRules.DEADLINE_PASSED);
    }
}
