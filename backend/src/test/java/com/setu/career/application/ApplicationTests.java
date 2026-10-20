package com.setu.career.application;

import com.setu.career.domain.ProficiencyLevel;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 12 §Career: applications/placement_records — apply (with eligibility gate), duplicate prevention, lifecycle transitions, placement recording, authorization. Deliberately NOT {@code @Transactional} — {@code ApplicationInsertGuard} uses REQUIRES_NEW. */
@SpringBootTest
class ApplicationTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    SkillService skillService;
    @Autowired
    JobService jobService;
    @Autowired
    JobSkillService jobSkillService;
    @Autowired
    StudentSkillService studentSkillService;
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

    /** A job with NO required skills — every student is trivially eligible, keeping apply-focused tests independent of the eligibility gate itself (covered separately in CareerCoreTests). */
    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID placementOfficer = day12Fixtures.provisionPlacementOfficer(tenant, prefix + "PO");
        TestAuthentication.authenticateAs(placementOfficer);
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(tenant.tenantId(), "Role", "Employer", null)).jobId();
        TestAuthentication.clear();
        return new Scenario(tenant, student, jobId, placementOfficer);
    }

    @Test
    void studentCanApplyToOpenJobWithNoRequirements() {
        var s = setUp("APP1");
        TestAuthentication.authenticateAs(s.student.userId());

        var result = applicationService.apply(s.jobId, s.student.studentId());

        assertThat(result).isInstanceOf(ApplicationService.ApplyResult.Success.class);
    }

    @Test
    void duplicateApplicationIsRejected() {
        var s = setUp("APP2");
        TestAuthentication.authenticateAs(s.student.userId());
        applicationService.apply(s.jobId, s.student.studentId());

        var second = applicationService.apply(s.jobId, s.student.studentId());

        assertThat(second).isInstanceOf(ApplicationService.ApplyResult.AlreadyApplied.class);
    }

    @Test
    void ineligibleStudentCannotApply() {
        var s = setUp("APP3");
        TestAuthentication.authenticateAs(s.placementOfficer);
        UUID skillId = ((SkillService.CreateResult.Success)
            skillService.create(s.tenant.tenantId(), "K8S", "Kubernetes", null)).skillId();
        UUID strictJobId = ((JobService.CreateResult.Success)
            jobService.create(s.tenant.tenantId(), "DevOps", "Employer", null)).jobId();
        jobSkillService.add(strictJobId, skillId, ProficiencyLevel.EXPERT);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.student.userId());
        var result = applicationService.apply(strictJobId, s.student.studentId());

        assertThat(result).isInstanceOf(ApplicationService.ApplyResult.NotEligible.class);
        assertThat(((ApplicationService.ApplyResult.NotEligible) result).missingOrInsufficientSkillIds()).containsExactly(skillId);
    }

    @Test
    void cannotApplyToClosedJob() {
        var s = setUp("APP4");
        TestAuthentication.authenticateAs(s.placementOfficer);
        jobService.close(s.jobId);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.student.userId());
        var result = applicationService.apply(s.jobId, s.student.studentId());

        assertThat(result).isInstanceOf(ApplicationService.ApplyResult.JobNotOpen.class);
    }

    @Test
    void placementOfficerDrivesFullLifecycleToSelected() {
        var s = setUp("APP5");
        TestAuthentication.authenticateAs(s.student.userId());
        UUID applicationId = ((ApplicationService.ApplyResult.Success)
            applicationService.apply(s.jobId, s.student.studentId())).applicationId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.placementOfficer);
        assertThat(applicationService.shortlist(applicationId)).isInstanceOf(ApplicationService.TransitionResult.Success.class);
        assertThat(applicationService.select(applicationId)).isInstanceOf(ApplicationService.TransitionResult.Success.class);

        var afterTerminal = applicationService.reject(applicationId, "Not taken forward");
        assertThat(afterTerminal).isInstanceOf(ApplicationService.TransitionResult.InvalidTransition.class);
    }

    @Test
    void studentCanWithdrawOwnApplicationButNotSomeoneElses() {
        var s = setUp("APP6");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant,
            fixtures.provisionProgram(s.tenant, fixtures.provisionDepartment(s.tenant, "APP6D2"), "APP6P2"),
            fixtures.provisionCurriculum(s.tenant, fixtures.provisionProgram(s.tenant, fixtures.provisionDepartment(s.tenant, "APP6D3"), "APP6P3"), "v1", LocalDate.of(2025, 1, 1), null),
            "APP6O");
        TestAuthentication.authenticateAs(s.student.userId());
        UUID applicationId = ((ApplicationService.ApplyResult.Success)
            applicationService.apply(s.jobId, s.student.studentId())).applicationId();

        var ownWithdraw = applicationService.withdraw(applicationId);
        assertThat(ownWithdraw).isInstanceOf(ApplicationService.TransitionResult.Success.class);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.placementOfficer);
        UUID secondJobId = ((JobService.CreateResult.Success) jobService.create(s.tenant.tenantId(), "Role2", "Employer", null)).jobId();
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(other.userId());
        UUID otherApplicationId = ((ApplicationService.ApplyResult.Success)
            applicationService.apply(secondJobId, other.studentId())).applicationId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.student.userId());
        assertThatThrownBy(() -> applicationService.withdraw(otherApplicationId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void studentCannotShortlistOwnApplication() {
        var s = setUp("APP7");
        TestAuthentication.authenticateAs(s.student.userId());
        UUID applicationId = ((ApplicationService.ApplyResult.Success)
            applicationService.apply(s.jobId, s.student.studentId())).applicationId();

        assertThatThrownBy(() -> applicationService.shortlist(applicationId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void placementOfficerRecordsPlacementAndStudentCanReadOwnRecord() {
        var s = setUp("APP8");
        TestAuthentication.authenticateAs(s.placementOfficer);

        var result = placementRecordService.record(s.student.studentId(), s.jobId, "Acme Corp", "SDE", new BigDecimal("1200000"), LocalDate.of(2026, 6, 1));
        assertThat(result).isInstanceOf(PlacementRecordService.RecordResult.Success.class);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.student.userId());
        assertThat(placementRecordService.listForStudent(s.student.studentId())).hasSize(1);
    }

    @Test
    void studentCannotRecordOwnPlacement() {
        var s = setUp("APP9");
        TestAuthentication.authenticateAs(s.student.userId());

        assertThatThrownBy(() -> placementRecordService.record(s.student.studentId(), s.jobId, "Acme", "SDE", null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §placement: tenant-wide listing for the Placement Officer overview page — previously the
    // only read path was per-student, with no way to see every placement across the tenant at once. ---

    @Test
    void placementOfficerCanListAllPlacementsForTheTenant() {
        var s = setUp("APP10");
        TestAuthentication.authenticateAs(s.placementOfficer);
        placementRecordService.record(s.student.studentId(), s.jobId, "Acme Corp", "SDE", new BigDecimal("1200000"), LocalDate.of(2026, 6, 1));

        var records = placementRecordService.listForTenant(s.tenant.tenantId());

        assertThat(records).hasSize(1);
        assertThat(records.get(0).studentId()).isEqualTo(s.student.studentId());
    }

    @Test
    void studentCannotListTheTenantsPlacements() {
        var s = setUp("APP11");
        TestAuthentication.authenticateAs(s.student.userId());

        assertThatThrownBy(() -> placementRecordService.listForTenant(s.tenant.tenantId()))
            .isInstanceOf(AccessDeniedException.class);
    }
}
