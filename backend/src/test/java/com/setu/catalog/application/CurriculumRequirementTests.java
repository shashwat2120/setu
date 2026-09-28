package com.setu.catalog.application;

import com.setu.catalog.domain.RequirementRole;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Day 7 §16 REQUIREMENTS test matrix. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class CurriculumRequirementTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    CurriculumRequirementService requirementService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Fixture(Day7TestFixtures.Tenant tenant, UUID curriculumId, UUID courseId) {
    }

    private Fixture setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        return new Fixture(tenant, curriculumId, courseId);
    }

    @Test
    void validRequirementCreation() {
        var f = setUp("REQ1");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = requirementService.create(f.curriculumId, "CORE", new BigDecimal("30.0"), 8, "{\"note\":\"core\"}");

        assertThat(result).isInstanceOf(CurriculumRequirementService.CreateResult.Success.class);
    }

    @Test
    void negativeCreditsAreRejected() {
        var f = setUp("REQ2");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = requirementService.create(f.curriculumId, "CORE", new BigDecimal("-1"), 8, null);

        assertThat(result).isInstanceOf(CurriculumRequirementService.CreateResult.InvalidData.class);
    }

    @Test
    void negativeCoursesAreRejected() {
        var f = setUp("REQ3");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = requirementService.create(f.curriculumId, "CORE", new BigDecimal("10"), -2, null);

        assertThat(result).isInstanceOf(CurriculumRequirementService.CreateResult.InvalidData.class);
    }

    @Test
    void malformedRuleJsonIsRejected() {
        var f = setUp("REQ4");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = requirementService.create(f.curriculumId, "CORE", new BigDecimal("10"), 2, "not json {");

        assertThat(result).isInstanceOf(CurriculumRequirementService.CreateResult.InvalidRuleJson.class);
    }

    @Test
    void duplicateCourseMappingIsRejected() {
        var f = setUp("REQ5");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        UUID requirementId = ((CurriculumRequirementService.CreateResult.Success)
            requirementService.create(f.curriculumId, "CORE", new BigDecimal("10"), 2, null)).requirementId();
        requirementService.addCourse(requirementId, f.courseId, RequirementRole.CORE);

        var result = requirementService.addCourse(requirementId, f.courseId, RequirementRole.ELECTIVE);

        assertThat(result).isInstanceOf(CurriculumRequirementService.AddCourseResult.DuplicateMapping.class);
    }

    @Test
    void crossTenantCourseIsRejected() {
        var f = setUp("REQ6A");
        var otherTenant = fixtures.provisionTenant("REQ6B");
        UUID otherDept = fixtures.provisionDepartment(otherTenant, "REQ6BD");
        UUID otherCourse = fixtures.provisionCourse(otherTenant, otherDept, "REQ6BC");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        UUID requirementId = ((CurriculumRequirementService.CreateResult.Success)
            requirementService.create(f.curriculumId, "CORE", new BigDecimal("10"), 2, null)).requirementId();

        var result = requirementService.addCourse(requirementId, otherCourse, RequirementRole.CORE);

        assertThat(result).isInstanceOf(CurriculumRequirementService.AddCourseResult.CourseTenantMismatch.class);
    }

    @Test
    void removeCourseSucceedsThenNotFoundOnSecondRemoval() {
        var f = setUp("REQ7");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        UUID requirementId = ((CurriculumRequirementService.CreateResult.Success)
            requirementService.create(f.curriculumId, "CORE", new BigDecimal("10"), 2, null)).requirementId();
        requirementService.addCourse(requirementId, f.courseId, RequirementRole.CORE);

        var first = requirementService.removeCourse(requirementId, f.courseId);
        var second = requirementService.removeCourse(requirementId, f.courseId);

        assertThat(first).isEqualTo(CurriculumRequirementService.RemoveCourseResult.SUCCESS);
        assertThat(second).isEqualTo(CurriculumRequirementService.RemoveCourseResult.NOT_FOUND);
    }
}
