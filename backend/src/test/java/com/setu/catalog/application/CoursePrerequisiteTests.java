package com.setu.catalog.application;

import com.setu.catalog.domain.PrerequisiteRuleType;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Day 7 §7/§16 PREREQUISITES test matrix, including the mandatory A-&gt;B-&gt;C / A-&gt;B-&gt;A / A-&gt;A / cross-tenant / duplicate cases. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class CoursePrerequisiteTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    CoursePrerequisiteService prerequisiteService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void validEdgeIsAccepted() {
        var tenant = fixtures.provisionTenant("PRE1");
        UUID dept = fixtures.provisionDepartment(tenant, "PRE1D");
        UUID a = fixtures.provisionCourse(tenant, dept, "PRE1-A");
        UUID b = fixtures.provisionCourse(tenant, dept, "PRE1-B");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = prerequisiteService.addPrerequisite(a, b, PrerequisiteRuleType.REQUIRED, "C");

        assertThat(result).isInstanceOf(CoursePrerequisiteService.AddResult.Success.class);
    }

    @Test
    void selfLoopIsRejected() {
        var tenant = fixtures.provisionTenant("PRE2");
        UUID dept = fixtures.provisionDepartment(tenant, "PRE2D");
        UUID a = fixtures.provisionCourse(tenant, dept, "PRE2-A");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = prerequisiteService.addPrerequisite(a, a, PrerequisiteRuleType.REQUIRED, null);

        assertThat(result).isInstanceOf(CoursePrerequisiteService.AddResult.SelfReference.class);
    }

    @Test
    void aRequiresBThenBRequiresCIsValidChain() {
        var tenant = fixtures.provisionTenant("PRE3");
        UUID dept = fixtures.provisionDepartment(tenant, "PRE3D");
        UUID a = fixtures.provisionCourse(tenant, dept, "PRE3-A");
        UUID b = fixtures.provisionCourse(tenant, dept, "PRE3-B");
        UUID c = fixtures.provisionCourse(tenant, dept, "PRE3-C");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        prerequisiteService.addPrerequisite(a, b, PrerequisiteRuleType.REQUIRED, null);

        var result = prerequisiteService.addPrerequisite(b, c, PrerequisiteRuleType.REQUIRED, null);

        assertThat(result).isInstanceOf(CoursePrerequisiteService.AddResult.Success.class);
    }

    @Test
    void directCycleAToBThenBToAIsRejected() {
        var tenant = fixtures.provisionTenant("PRE4");
        UUID dept = fixtures.provisionDepartment(tenant, "PRE4D");
        UUID a = fixtures.provisionCourse(tenant, dept, "PRE4-A");
        UUID b = fixtures.provisionCourse(tenant, dept, "PRE4-B");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        prerequisiteService.addPrerequisite(a, b, PrerequisiteRuleType.REQUIRED, null);

        var result = prerequisiteService.addPrerequisite(b, a, PrerequisiteRuleType.REQUIRED, null);

        assertThat(result).isInstanceOf(CoursePrerequisiteService.AddResult.WouldCreateCycle.class);
    }

    @Test
    void indirectCycleAToBToCThenCToAIsRejected() {
        var tenant = fixtures.provisionTenant("PRE5");
        UUID dept = fixtures.provisionDepartment(tenant, "PRE5D");
        UUID a = fixtures.provisionCourse(tenant, dept, "PRE5-A");
        UUID b = fixtures.provisionCourse(tenant, dept, "PRE5-B");
        UUID c = fixtures.provisionCourse(tenant, dept, "PRE5-C");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        prerequisiteService.addPrerequisite(a, b, PrerequisiteRuleType.REQUIRED, null);
        prerequisiteService.addPrerequisite(b, c, PrerequisiteRuleType.REQUIRED, null);

        var result = prerequisiteService.addPrerequisite(c, a, PrerequisiteRuleType.REQUIRED, null);

        assertThat(result).isInstanceOf(CoursePrerequisiteService.AddResult.WouldCreateCycle.class);
    }

    @Test
    void duplicateEdgeIsRejected() {
        var tenant = fixtures.provisionTenant("PRE6");
        UUID dept = fixtures.provisionDepartment(tenant, "PRE6D");
        UUID a = fixtures.provisionCourse(tenant, dept, "PRE6-A");
        UUID b = fixtures.provisionCourse(tenant, dept, "PRE6-B");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        prerequisiteService.addPrerequisite(a, b, PrerequisiteRuleType.REQUIRED, null);

        var result = prerequisiteService.addPrerequisite(a, b, PrerequisiteRuleType.RECOMMENDED, null);

        assertThat(result).isInstanceOf(CoursePrerequisiteService.AddResult.DuplicateEdge.class);
    }

    @Test
    void wrongTenantPrerequisiteIsRejected() {
        var tenantA = fixtures.provisionTenant("PRE7A");
        var tenantB = fixtures.provisionTenant("PRE7B");
        UUID deptA = fixtures.provisionDepartment(tenantA, "PRE7AD");
        UUID deptB = fixtures.provisionDepartment(tenantB, "PRE7BD");
        UUID a = fixtures.provisionCourse(tenantA, deptA, "PRE7-A");
        UUID bOther = fixtures.provisionCourse(tenantB, deptB, "PRE7-B");
        TestAuthentication.authenticateAs(tenantA.adminUserId());

        var result = prerequisiteService.addPrerequisite(a, bOther, PrerequisiteRuleType.REQUIRED, null);

        assertThat(result).isInstanceOf(CoursePrerequisiteService.AddResult.TenantMismatch.class);
    }

    @Test
    void invalidMinimumGradeIsRejected() {
        var tenant = fixtures.provisionTenant("PRE8");
        UUID dept = fixtures.provisionDepartment(tenant, "PRE8D");
        UUID a = fixtures.provisionCourse(tenant, dept, "PRE8-A");
        UUID b = fixtures.provisionCourse(tenant, dept, "PRE8-B");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = prerequisiteService.addPrerequisite(a, b, PrerequisiteRuleType.REQUIRED, "Z");

        assertThat(result).isInstanceOf(CoursePrerequisiteService.AddResult.InvalidMinimumGrade.class);
    }
}
