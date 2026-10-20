package com.setu.career.application;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selecting a student records their placement in the same transaction, from the job and the officer's offer: the employer and role are the job's, the package is the offer's
 * (the job's own when none is given) in rupees a year, the placement is dated today and the student joins on the date given. Nothing is recorded for any other decision.
 * Deliberately not {@code @Transactional}: applying uses its own transaction.
 */
@SpringBootTest
class ApplicationOfferTests {

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
    JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Setup(Day8TestFixtures.Student student, UUID officer, UUID applicationId, UUID jobId) {
    }

    private Setup setUp(String prefix, BigDecimal jobPackageLpa) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID officer = day12Fixtures.provisionPlacementOfficer(tenant, prefix + "PO");
        TestAuthentication.authenticateAs(officer);
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(tenant.tenantId(), "Data Analyst", "Example Co", "d",
            new JobTerms(null, null, null, jobPackageLpa, null, null, null, null))).jobId();
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(student.userId());
        UUID applicationId = ((ApplicationService.ApplyResult.Success) applicationService.apply(jobId, student.studentId())).applicationId();
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(officer);
        applicationService.shortlist(applicationId);
        return new Setup(student, officer, applicationId, jobId);
    }

    private List<Map<String, Object>> placements(UUID studentId) {
        return jdbc.queryForList("SELECT employer, role, ctc, placed_at, joining_date, job_id FROM career.placement_records WHERE student_id = ?", studentId);
    }

    @Test
    void selectingRecordsThePlacementFromTheJobWithTheOffersPackageAndJoiningDate() {
        var s = setUp("OF1", new BigDecimal("8.5"));
        LocalDate joining = LocalDate.now().plusMonths(3);

        var result = applicationService.select(s.applicationId, "Strong interview", new ApplicationService.Offer(new BigDecimal("9.25"), joining));

        assertThat(result).isInstanceOf(ApplicationService.TransitionResult.Success.class);
        assertThat(placements(s.student.studentId())).singleElement().satisfies(p -> {
            assertThat(p.get("employer")).isEqualTo("Example Co");
            assertThat(p.get("role")).isEqualTo("Data Analyst");
            assertThat((BigDecimal) p.get("ctc")).isEqualByComparingTo("925000");
            assertThat(p.get("placed_at")).isEqualTo(java.sql.Date.valueOf(LocalDate.now()));
            assertThat(p.get("joining_date")).isEqualTo(java.sql.Date.valueOf(joining));
            assertThat(p.get("job_id")).isEqualTo(s.jobId);
        });
    }

    @Test
    void withoutAnOfferTheJobsOwnPackageIsUsedAndNoJoiningDateIsInvented() {
        var s = setUp("OF2", new BigDecimal("8.5"));

        applicationService.select(s.applicationId);

        assertThat(placements(s.student.studentId())).singleElement().satisfies(p -> {
            assertThat((BigDecimal) p.get("ctc")).isEqualByComparingTo("850000");
            assertThat(p.get("joining_date")).isNull();
        });
    }

    @Test
    void aJobWithNoPackageAndNoOfferRecordsNoCtcRatherThanZero() {
        var s = setUp("OF3", null);

        applicationService.select(s.applicationId);

        assertThat(placements(s.student.studentId())).singleElement().satisfies(p -> assertThat(p.get("ctc")).isNull());
    }

    @Test
    void anOfferThatMakesNoSenseIsRefusedAndNothingIsSelectedOrRecorded() {
        var s = setUp("OF4", null);

        var past = applicationService.select(s.applicationId, null, new ApplicationService.Offer(null, LocalDate.now().minusDays(1)));
        var negative = applicationService.select(s.applicationId, null, new ApplicationService.Offer(new BigDecimal("-1"), null));
        var absurd = applicationService.select(s.applicationId, null, new ApplicationService.Offer(new BigDecimal("1001"), null));

        assertThat(past).isEqualTo(new ApplicationService.TransitionResult.InvalidOffer("the joining date cannot be in the past"));
        assertThat(negative).isInstanceOf(ApplicationService.TransitionResult.InvalidOffer.class);
        assertThat(absurd).isInstanceOf(ApplicationService.TransitionResult.InvalidOffer.class);
        assertThat(jdbc.queryForObject("SELECT status FROM career.applications WHERE id = ?", String.class, s.applicationId)).isEqualTo("SHORTLISTED");
        assertThat(placements(s.student.studentId())).isEmpty();
    }

    @Test
    void shortlistingAndRejectingRecordNoPlacement() {
        var s = setUp("OF5", new BigDecimal("8.5"));

        applicationService.reject(s.applicationId, "Position filled");

        assertThat(placements(s.student.studentId())).isEmpty();
    }
}
