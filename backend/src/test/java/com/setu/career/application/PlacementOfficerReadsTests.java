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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A placement officer shortlists applicants, so they must be able to read the skills and eligibility those decisions rest on (managing implies viewing, as it
 * already did for the jobs themselves); and only in their own college, and never for someone with no standing at all (another student).
 */
@SpringBootTest
class PlacementOfficerReadsTests {

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

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Setup(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student, Day8TestFixtures.Student other, UUID officer, UUID jobId, UUID skillId) {
    }

    private Setup setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        var other = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "O");
        UUID officer = day12Fixtures.provisionPlacementOfficer(tenant, prefix + "PO");
        TestAuthentication.authenticateAs(officer);
        UUID skillId = ((SkillService.CreateResult.Success) skillService.create(tenant.tenantId(), "SQL", "SQL", null)).skillId();
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(tenant.tenantId(), "Analyst", "Employer", null)).jobId();
        jobSkillService.add(jobId, skillId, ProficiencyLevel.INTERMEDIATE);
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(student.userId());
        studentSkillService.add(student.studentId(), skillId, ProficiencyLevel.ADVANCED, null);
        TestAuthentication.clear();
        return new Setup(tenant, student, other, officer, jobId, skillId);
    }

    @Test
    void theOfficerReadsAStudentsSkillsAndSeesWhetherTheyAreEligible() {
        var s = setUp("POR1");
        TestAuthentication.authenticateAs(s.officer);

        assertThat(studentSkillService.listForStudent(s.student.studentId())).extracting(k -> k.skillId()).containsExactly(s.skillId);
        var preview = applicationService.previewEligibility(s.jobId, s.student.studentId()).orElseThrow();
        assertThat(preview.eligible()).isTrue();
        var notYet = applicationService.previewEligibility(s.jobId, s.other.studentId()).orElseThrow();
        assertThat(notYet.eligible()).isFalse();
        assertThat(notYet.missingOrInsufficientSkillIds()).containsExactly(s.skillId);
    }

    @Test
    void anotherStudentStillCannotReadSomeoneElsesSkillsOrEligibility() {
        var s = setUp("POR2");
        TestAuthentication.authenticateAs(s.other.userId());

        assertThatThrownBy(() -> studentSkillService.listForStudent(s.student.studentId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> applicationService.previewEligibility(s.jobId, s.student.studentId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anOfficerOfAnotherCollegeCannotReadThisCollegesStudents() {
        var s = setUp("POR3A");
        var otherTenant = fixtures.provisionTenant("POR3B");
        UUID otherOfficer = day12Fixtures.provisionPlacementOfficer(otherTenant, "POR3BPO");
        TestAuthentication.authenticateAs(otherOfficer);

        assertThatThrownBy(() -> studentSkillService.listForStudent(s.student.studentId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> applicationService.previewEligibility(s.jobId, s.student.studentId())).isInstanceOf(AccessDeniedException.class);
    }
}
