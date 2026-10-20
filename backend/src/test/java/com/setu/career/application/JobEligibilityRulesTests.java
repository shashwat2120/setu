package com.setu.career.application;

import com.setu.career.domain.JobTerms;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The job's deadline and academic rules in the real apply flow: a student who does not meet one cannot apply and is told which, a student who does can, and the
 * rules are decided from the student's real attendance, grades and program. Not {@code @Transactional}: applying uses a separate connection for its insert guard.
 */
@SpringBootTest
class JobEligibilityRulesTests {

    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    JobService jobService;
    @Autowired
    ApplicationService applicationService;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Setup(Day13TestFixtures.Scenario scenario, UUID officer) {
    }

    private Setup setUp(String prefix) {
        var s = day13Fixtures.provisionEnrolledScenario(prefix);
        return new Setup(s, day12Fixtures.provisionPlacementOfficer(s.tenant(), prefix + "PO"));
    }

    private UUID postJob(Setup setup, JobTerms terms) {
        TestAuthentication.authenticateAs(setup.officer());
        try {
            return ((JobService.CreateResult.Success) jobService.create(setup.scenario().tenant().tenantId(), "Role", "Employer", "A job", terms)).jobId();
        } finally {
            TestAuthentication.clear();
        }
    }

    private static JobTerms rules(String minCgpa, String minAttendance, List<UUID> programs, Instant deadline) {
        return new JobTerms(minCgpa == null ? null : new BigDecimal(minCgpa), minAttendance == null ? null : new BigDecimal(minAttendance), deadline,
            null, null, null, null, programs);
    }

    private ApplicationService.ApplyResult applyAs(Setup setup, UUID jobId) {
        TestAuthentication.authenticateAs(setup.scenario().student().userId());
        try {
            return applicationService.apply(jobId, setup.scenario().student().studentId());
        } finally {
            TestAuthentication.clear();
        }
    }

    private static List<String> codesOf(ApplicationService.ApplyResult result) {
        return ((ApplicationService.ApplyResult.NotEligible) result).unmetRules().stream().map(ApplicationService.UnmetRule::code).toList();
    }

    private static JobTerms forBatch(int year) {
        return new JobTerms(null, null, null, null, null, null, null, null, year);
    }

    /** The fixture admits students in 2024 to an 8-term program; with the college's default of two terms a year that is the batch of 2028. */
    @Test
    void aJobForTheStudentsOwnBatchAcceptsThemAndOneForAnotherBatchRefusesWithTheirs() {
        var setup = setUp("JER20");

        assertThat(applyAs(setup, postJob(setup, forBatch(2028)))).isInstanceOf(ApplicationService.ApplyResult.Success.class);
        var refused = applyAs(setup, postJob(setup, forBatch(2027)));

        assertThat(codesOf(refused)).containsExactly(EligibilityRules.GRADUATING_YEAR);
        assertThat(((ApplicationService.ApplyResult.NotEligible) refused).unmetRules().get(0).message()).isEqualTo("This job is for the batch of 2027; yours is 2028.");
    }

    @Test
    void aCollegeWithThreeTermsAYearGraduatesTheSameStudentAYearEarlier() {
        var setup = setUp("JER21");
        jdbc.update("UPDATE institution.colleges SET settings_json = jsonb_set(settings_json, '{terms_per_year}', '3') WHERE id = ?", setup.scenario().tenant().tenantId());

        // 8 terms at 3 a year is 3 years (rounded up): admitted 2024, so the batch of 2027
        assertThat(applyAs(setup, postJob(setup, forBatch(2027)))).isInstanceOf(ApplicationService.ApplyResult.Success.class);
        assertThat(codesOf(applyAs(setup, postJob(setup, forBatch(2028))))).containsExactly(EligibilityRules.GRADUATING_YEAR);
    }

    @Test
    void theBatchIsStoredWithTheJobAndNothingIsStoredWhenNoneIsSet() {
        var setup = setUp("JER22");

        UUID forBatch = postJob(setup, forBatch(2028));
        UUID open = postJob(setup, JobTerms.NONE);

        assertThat(jdbc.queryForObject("SELECT graduating_year FROM career.jobs WHERE id = ?", Integer.class, forBatch)).isEqualTo(2028);
        assertThat(jdbc.queryForObject("SELECT graduating_year FROM career.jobs WHERE id = ?", Integer.class, open)).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE career.jobs SET graduating_year = 1999 WHERE id = ?", open)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aJobWithoutRulesAcceptsAStudentWithNoGradesAndNoAttendance() {
        var setup = setUp("JER1");

        assertThat(applyAs(setup, postJob(setup, JobTerms.NONE))).isInstanceOf(ApplicationService.ApplyResult.Success.class);
    }

    @Test
    void attendanceBelowTheMinimumRefusesTheApplicationAndSaysSo() {
        var setup = setUp("JER2");
        day13Fixtures.recordAttendanceSession(setup.scenario(), true);
        day13Fixtures.recordAttendanceSession(setup.scenario(), false);   // 1 of 2: 50%
        UUID job = postJob(setup, rules(null, "75", null, null));

        var result = applyAs(setup, job);

        assertThat(result).isInstanceOf(ApplicationService.ApplyResult.NotEligible.class);
        assertThat(codesOf(result)).containsExactly(EligibilityRules.MIN_ATTENDANCE);
        assertThat(((ApplicationService.ApplyResult.NotEligible) result).unmetRules().get(0).message()).isEqualTo("Needs attendance of at least 75%; yours is 50%.");
    }

    @Test
    void attendanceAtTheMinimumAllowsTheApplication() {
        var setup = setUp("JER3");
        day13Fixtures.recordAttendanceSession(setup.scenario(), true);
        day13Fixtures.recordAttendanceSession(setup.scenario(), false);   // 50%

        assertThat(applyAs(setup, postJob(setup, rules(null, "50", null, null)))).isInstanceOf(ApplicationService.ApplyResult.Success.class);
    }

    @Test
    void noSessionHeldYetCannotMeetAnAttendanceRule() {
        var setup = setUp("JER4");

        var result = applyAs(setup, postJob(setup, rules(null, "1", null, null)));

        assertThat(codesOf(result)).containsExactly(EligibilityRules.MIN_ATTENDANCE);
        assertThat(((ApplicationService.ApplyResult.NotEligible) result).unmetRules().get(0).message()).contains("no class session has been held");
    }

    @Test
    void aCgpaBelowTheMinimumRefusesAndAHighEnoughOneAllows() {
        var setup = setUp("JER5");
        day13Fixtures.recordGrade(setup.scenario(), "C", new BigDecimal("2.0"));
        UUID demanding = postJob(setup, rules("3.0", null, null, null));
        UUID reasonable = postJob(setup, rules("2.0", null, null, null));

        var refused = applyAs(setup, demanding);

        assertThat(codesOf(refused)).containsExactly(EligibilityRules.MIN_CGPA);
        assertThat(((ApplicationService.ApplyResult.NotEligible) refused).unmetRules().get(0).message()).isEqualTo("Needs a CGPA of at least 3; yours is 2.");
        assertThat(applyAs(setup, reasonable)).isInstanceOf(ApplicationService.ApplyResult.Success.class);
    }

    @Test
    void noPublishedGradesCannotMeetACgpaRule() {
        var setup = setUp("JER6");

        var result = applyAs(setup, postJob(setup, rules("1.0", null, null, null)));

        assertThat(codesOf(result)).containsExactly(EligibilityRules.MIN_CGPA);
        assertThat(((ApplicationService.ApplyResult.NotEligible) result).unmetRules().get(0).message()).contains("no published grades yet");
    }

    @Test
    void aJobOpenOnlyToOtherProgramsRefusesAndOneListingTheirProgramAllows() {
        var setup = setUp("JER7");
        UUID otherProgram = UUID.randomUUID();
        UUID wrongProgram = postJob(setup, rules(null, null, List.of(otherProgram), null));
        UUID rightProgram = postJob(setup, rules(null, null, List.of(otherProgram, setup.scenario().programId()), null));

        assertThat(codesOf(applyAs(setup, wrongProgram))).containsExactly(EligibilityRules.PROGRAM_NOT_ALLOWED);
        assertThat(applyAs(setup, rightProgram)).isInstanceOf(ApplicationService.ApplyResult.Success.class);
    }

    @Test
    void aDeadlineInThePastRefusesAndOneAheadAllows() {
        var setup = setUp("JER8");
        UUID ahead = postJob(setup, rules(null, null, null, Instant.now().plus(1, ChronoUnit.DAYS)));
        UUID passing = postJob(setup, rules(null, null, null, Instant.now().plus(1, ChronoUnit.HOURS)));
        jdbc.update("update career.jobs set application_deadline = now() - interval '1 minute' where id = ?", passing);

        assertThat(applyAs(setup, ahead)).isInstanceOf(ApplicationService.ApplyResult.Success.class);
        var late = applyAs(setup, passing);
        assertThat(codesOf(late)).containsExactly(EligibilityRules.DEADLINE_PASSED);
    }

    @Test
    void theEligibilityPreviewListsEveryUnmetRuleAndTheSkillsSeparately() {
        var setup = setUp("JER9");
        UUID job = postJob(setup, rules("3.5", "90", List.of(UUID.randomUUID()), null));

        TestAuthentication.authenticateAs(setup.scenario().student().userId());
        var preview = applicationService.previewEligibility(job, setup.scenario().student().studentId()).orElseThrow();

        assertThat(preview.eligible()).isFalse();
        assertThat(preview.missingOrInsufficientSkillIds()).isEmpty();
        assertThat(preview.unmetRules()).extracting(ApplicationService.UnmetRule::code)
            .containsExactly(EligibilityRules.MIN_CGPA, EligibilityRules.MIN_ATTENDANCE, EligibilityRules.PROGRAM_NOT_ALLOWED);
    }

    @Test
    void aPlacementOfficerCanPreviewWhetherAStudentIsEligibleAndSeesTheSameAnswer() {
        var setup = setUp("JER10");
        day13Fixtures.recordGrade(setup.scenario(), "C", new BigDecimal("2.0"));
        UUID job = postJob(setup, rules("3.0", null, null, null));

        TestAuthentication.authenticateAs(setup.officer());
        var preview = applicationService.previewEligibility(job, setup.scenario().student().studentId()).orElseThrow();

        assertThat(preview.eligible()).isFalse();
        assertThat(preview.unmetRules()).singleElement().satisfies(u -> assertThat(u.code()).isEqualTo(EligibilityRules.MIN_CGPA));
    }

    @Test
    void theTermsAreStoredAndReadBackAndEditedWithTheVersionCheck() {
        var setup = setUp("JER11");
        UUID program = setup.scenario().programId();
        Instant deadline = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        var terms = new JobTerms(new BigDecimal("7.5"), new BigDecimal("80"), deadline, new BigDecimal("12.5"), "Pune", 3,
            com.setu.career.domain.JobType.FULL_TIME, List.of(program));
        UUID job = postJob(setup, terms);

        TestAuthentication.authenticateAs(setup.officer());
        var stored = jobService.get(job).orElseThrow();
        assertSameTerms(stored.terms(), terms);

        // an edit of the wording alone leaves the rules as they were
        assertThat(jobService.update(job, "New title", "Employer", "A job", stored.version())).isInstanceOf(JobService.UpdateResult.Success.class);
        assertSameTerms(jobService.get(job).orElseThrow().terms(), terms);
        // an edit that gives terms replaces them, and an empty one clears every rule
        var reloaded = jobService.get(job).orElseThrow();
        assertThat(jobService.update(job, "New title", "Employer", "A job", JobTerms.NONE, reloaded.version())).isInstanceOf(JobService.UpdateResult.Success.class);
        assertThat(jobService.get(job).orElseThrow().terms()).isEqualTo(JobTerms.NONE);
    }

    /** The database keeps a CGPA as 7.50 where the officer typed 7.5, which a record's equals would call different: compare the numbers as numbers. */
    private static void assertSameTerms(JobTerms actual, JobTerms expected) {
        assertThat(actual.minCgpa()).isEqualByComparingTo(expected.minCgpa());
        assertThat(actual.minAttendancePct()).isEqualByComparingTo(expected.minAttendancePct());
        assertThat(actual.packageLpa()).isEqualByComparingTo(expected.packageLpa());
        assertThat(actual.applicationDeadline()).isEqualTo(expected.applicationDeadline());
        assertThat(actual.location()).isEqualTo(expected.location());
        assertThat(actual.openings()).isEqualTo(expected.openings());
        assertThat(actual.jobType()).isEqualTo(expected.jobType());
        assertThat(actual.allowedProgramIds()).isEqualTo(expected.allowedProgramIds());
    }

    @Test
    void aDeadlineThatIsAlreadyPastIsRefusedWhenPostedButLeftAloneOnAnEditOfSomethingElse() {
        var setup = setUp("JER12");
        TestAuthentication.authenticateAs(setup.officer());

        var refused = jobService.create(setup.scenario().tenant().tenantId(), "Role", "Employer", null, rules(null, null, null, Instant.now().minusSeconds(60)));
        assertThat(refused).isInstanceOfSatisfying(JobService.CreateResult.InvalidData.class, r -> assertThat(r.message()).contains("deadline must be in the future"));
        TestAuthentication.clear();

        UUID job = postJob(setup, rules(null, null, null, Instant.now().plus(1, ChronoUnit.HOURS)));
        jdbc.update("update career.jobs set application_deadline = now() - interval '1 minute' where id = ?", job);
        TestAuthentication.authenticateAs(setup.officer());
        var job1 = jobService.get(job).orElseThrow();
        var sameTermsNewWording = job1.terms();
        assertThat(jobService.update(job, "Reworded", "Employer", "A job", sameTermsNewWording, job1.version())).isInstanceOf(JobService.UpdateResult.Success.class);
    }

    @Test
    void termsThatMakeNoSenseAreRefusedWhenPosted() {
        var setup = setUp("JER13");
        TestAuthentication.authenticateAs(setup.officer());

        var result = jobService.create(setup.scenario().tenant().tenantId(), "Role", "Employer", null, rules("-1", null, null, null));

        assertThat(result).isInstanceOfSatisfying(JobService.CreateResult.InvalidData.class, r -> assertThat(r.message()).contains("CGPA"));
    }

    @Test
    void theDatabaseItselfRefusesImpossibleTerms() {
        var setup = setUp("JER14");
        UUID job = postJob(setup, JobTerms.NONE);

        assertThatThrownBy(() -> jdbc.update("update career.jobs set min_cgpa = 11 where id = ?", job)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update career.jobs set min_attendance_pct = 101 where id = ?", job)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update career.jobs set openings = 0 where id = ?", job)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update career.jobs set job_type = 'MAYBE' where id = ?", job)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
