package com.setu.career.application;

import com.setu.career.domain.ProficiencyLevel;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 12 §Career: skill_categories/skills/student_skills/jobs/job_skills —
 * CRUD, authorization (PLACEMENT_OFFICER manages the catalog, STUDENT
 * self-service only on their own skill profile, COLLEGE_ADMIN read-only,
 * FACULTY none), tenant isolation, validation, deterministic eligibility.
 * <p>
 * Deliberately NOT {@code @Transactional} — several insert guards here use
 * {@code REQUIRES_NEW} (same reasoning as FinanceCoreTests' class javadoc).
 */
@SpringBootTest
class CareerCoreTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    SkillCategoryService skillCategoryService;
    @Autowired
    SkillService skillService;
    @Autowired
    StudentSkillService studentSkillService;
    @Autowired
    JobService jobService;
    @Autowired
    JobSkillService jobSkillService;
    @Autowired
    ApplicationService applicationService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID placementOfficer) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        UUID placementOfficer = day12Fixtures.provisionPlacementOfficer(tenant, prefix + "PO");
        return new Scenario(tenant, programId, curriculumId, placementOfficer);
    }

    @Test
    void placementOfficerCanCreateSkillCatalogAndJob() {
        var s = setUp("CAR1");
        TestAuthentication.authenticateAs(s.placementOfficer);

        var categoryResult = skillCategoryService.create(s.tenant.tenantId(), "TECH", "Technical");
        assertThat(categoryResult).isInstanceOf(SkillCategoryService.CreateResult.Success.class);
        UUID categoryId = ((SkillCategoryService.CreateResult.Success) categoryResult).categoryId();

        var skillResult = skillService.create(s.tenant.tenantId(), "JAVA", "Java", categoryId);
        assertThat(skillResult).isInstanceOf(SkillService.CreateResult.Success.class);

        var jobResult = jobService.create(s.tenant.tenantId(), "Backend Engineer", "Acme Corp", "Java backend role");
        assertThat(jobResult).isInstanceOf(JobService.CreateResult.Success.class);
    }

    @Test
    void collegeAdminCanOnlyReadCareerData() {
        var s = setUp("CAR2");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        assertThatThrownBy(() -> skillCategoryService.create(s.tenant.tenantId(), "TECH", "Technical"))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(skillCategoryService.listForTenant(s.tenant.tenantId())).isEmpty();
    }

    @Test
    void facultyCannotAccessCareerManagement() {
        var s = setUp("CAR3");
        UUID facultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "CAR3-F");
        TestAuthentication.authenticateAs(facultyUserId);

        assertThatThrownBy(() -> jobService.create(s.tenant.tenantId(), "Role", "Employer", null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void duplicateSkillCodeInTenantIsRejected() {
        var s = setUp("CAR4");
        TestAuthentication.authenticateAs(s.placementOfficer);
        skillService.create(s.tenant.tenantId(), "PYTHON", "Python", null);

        var second = skillService.create(s.tenant.tenantId(), "PYTHON", "Python Again", null);

        assertThat(second).isInstanceOf(SkillService.CreateResult.CodeAlreadyInUse.class);
    }

    @Test
    void studentManagesOwnSkillProfileOnly() {
        var s = setUp("CAR5");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "CAR5S");
        TestAuthentication.authenticateAs(s.placementOfficer);
        UUID skillId = ((SkillService.CreateResult.Success)
            skillService.create(s.tenant.tenantId(), "SQL", "SQL", null)).skillId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(student.userId());
        var addResult = studentSkillService.add(student.studentId(), skillId, ProficiencyLevel.INTERMEDIATE, null);
        assertThat(addResult).isInstanceOf(StudentSkillService.AddResult.Success.class);
        assertThat(studentSkillService.listForStudent(student.studentId())).hasSize(1);
    }

    @Test
    void studentCannotManageAnotherStudentsSkillProfile() {
        var s = setUp("CAR6");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "CAR6S");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "CAR6O");
        TestAuthentication.authenticateAs(s.placementOfficer);
        UUID skillId = ((SkillService.CreateResult.Success)
            skillService.create(s.tenant.tenantId(), "GO", "Go", null)).skillId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(student.userId());
        assertThatThrownBy(() -> studentSkillService.add(other.studentId(), skillId, ProficiencyLevel.BEGINNER, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void eligibilityIsDeterministicAndExplainsMissingSkills() {
        var s = setUp("CAR7");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "CAR7S");
        TestAuthentication.authenticateAs(s.placementOfficer);
        UUID skillId = ((SkillService.CreateResult.Success)
            skillService.create(s.tenant.tenantId(), "REACT", "React", null)).skillId();
        UUID jobId = ((JobService.CreateResult.Success)
            jobService.create(s.tenant.tenantId(), "Frontend Engineer", "Acme", null)).jobId();
        jobSkillService.add(jobId, skillId, ProficiencyLevel.ADVANCED);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(student.userId());
        var beforeAnySkill = applicationService.previewEligibility(jobId, student.studentId()).orElseThrow();
        assertThat(beforeAnySkill.eligible()).isFalse();
        assertThat(beforeAnySkill.missingOrInsufficientSkillIds()).containsExactly(skillId);

        studentSkillService.add(student.studentId(), skillId, ProficiencyLevel.BEGINNER, null);
        var stillInsufficient = applicationService.previewEligibility(jobId, student.studentId()).orElseThrow();
        assertThat(stillInsufficient.eligible()).isFalse();
    }

    @Test
    void crossTenantPlacementOfficerCannotManageAnotherTenantsJobs() {
        var s1 = setUp("CAR8A");
        var s2 = setUp("CAR8B");
        TestAuthentication.authenticateAs(s2.placementOfficer);

        assertThatThrownBy(() -> jobService.create(s1.tenant.tenantId(), "Role", "Employer", null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void closingJobIsTerminalAndRejectsSecondAttempt() {
        var s = setUp("CAR9");
        TestAuthentication.authenticateAs(s.placementOfficer);
        UUID jobId = ((JobService.CreateResult.Success)
            jobService.create(s.tenant.tenantId(), "Role", "Employer", null)).jobId();

        var first = jobService.close(jobId);
        assertThat(first).isInstanceOf(JobService.CloseResult.Success.class);
        var second = jobService.close(jobId);
        assertThat(second).isInstanceOf(JobService.CloseResult.InvalidTransition.class);
    }

    // --- Day 16 §placement: real gap found live — PLACEMENT_OFFICER's role_permissions (V55) grant
    // MANAGE_CAREER_LISTINGS but never VIEW_CAREER_LISTINGS, so a placement officer could create jobs/skills
    // yet not list or view the very listings they manage. Fixed so MANAGE implies VIEW on all three.

    @Test
    void placementOfficerCanListTheJobsSkillsAndCategoriesTheyManage() {
        var s = setUp("CAR10");
        TestAuthentication.authenticateAs(s.placementOfficer);
        UUID categoryId = ((SkillCategoryService.CreateResult.Success)
            skillCategoryService.create(s.tenant.tenantId(), "TECH", "Technical")).categoryId();
        skillService.create(s.tenant.tenantId(), "RUST", "Rust", categoryId);
        UUID jobId = ((JobService.CreateResult.Success)
            jobService.create(s.tenant.tenantId(), "Role", "Employer", null)).jobId();

        assertThat(skillCategoryService.listForTenant(s.tenant.tenantId())).hasSize(1);
        assertThat(skillService.listForTenant(s.tenant.tenantId())).hasSize(1);
        assertThat(jobService.listForTenant(s.tenant.tenantId())).hasSize(1);
        assertThat(jobService.get(jobId)).isPresent();
    }

    // --- Day 16 §placement: real IDOR found live — JobSkillService#listForJob's javadoc claimed "same
    // visibility as the owning job" but nothing ever enforced that; any authenticated user of any tenant could
    // list any job's skill requirements by ID alone. Fixed to delegate to JobService#get first.

    @Test
    void unrelatedTenantsUserCannotListAnotherTenantsJobSkillRequirements() {
        var s1 = setUp("CAR11A");
        var s2 = setUp("CAR11B");
        TestAuthentication.authenticateAs(s1.placementOfficer);
        UUID skillId = ((SkillService.CreateResult.Success) skillService.create(s1.tenant.tenantId(), "GOLANG", "Go", null)).skillId();
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(s1.tenant.tenantId(), "Role", "Employer", null)).jobId();
        jobSkillService.add(jobId, skillId, ProficiencyLevel.ADVANCED);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s2.placementOfficer);
        assertThatThrownBy(() -> jobSkillService.listForJob(jobId)).isInstanceOf(AccessDeniedException.class);
    }
}
