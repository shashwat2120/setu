package com.setu.catalog.application;

import com.setu.catalog.domain.CourseStatus;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 7 §16 COURSES test matrix. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class CourseTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    CourseService courseService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void validCourseCreation() {
        var tenant = fixtures.provisionTenant("CRS1");
        UUID dept = fixtures.provisionDepartment(tenant, "CRS1D");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = courseService.create(dept, "CRS1-101", "Intro Course", new BigDecimal("3.0"), "LECTURE", "UG", null);

        assertThat(result).isInstanceOf(CourseService.CreateResult.Success.class);
    }

    @Test
    void duplicateCodeIsRejected() {
        var tenant = fixtures.provisionTenant("CRS2");
        UUID dept = fixtures.provisionDepartment(tenant, "CRS2D");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        courseService.create(dept, "DUP-101", "First", new BigDecimal("3.0"), null, null, null);

        var result = courseService.create(dept, "DUP-101", "Second", new BigDecimal("3.0"), null, null, null);

        assertThat(result).isInstanceOf(CourseService.CreateResult.CodeAlreadyInUse.class);
    }

    @Test
    void invalidCreditsAreRejected() {
        var tenant = fixtures.provisionTenant("CRS3");
        UUID dept = fixtures.provisionDepartment(tenant, "CRS3D");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = courseService.create(dept, "CRS3-101", "Bad Credits", new BigDecimal("0"), null, null, null);

        assertThat(result).isInstanceOf(CourseService.CreateResult.InvalidCourseData.class);
    }

    @Test
    void wrongDepartmentTenantIsRejected() {
        var tenantA = fixtures.provisionTenant("CRS4A");
        var tenantB = fixtures.provisionTenant("CRS4B");
        UUID deptB = fixtures.provisionDepartment(tenantB, "CRS4BD");
        TestAuthentication.authenticateAs(tenantA.adminUserId());

        assertThatThrownBy(() -> courseService.create(deptB, "CRS4-101", "Cross", new BigDecimal("3.0"), null, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void lifecycleTransitionsFollowStateMachine() {
        var tenant = fixtures.provisionTenant("CRS5");
        UUID dept = fixtures.provisionDepartment(tenant, "CRS5D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "CRS5-101");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        assertThat(courseService.changeStatus(courseId, CourseStatus.ACTIVE)).isEqualTo(CourseService.StatusChangeResult.SUCCESS);
        assertThat(courseService.changeStatus(courseId, CourseStatus.DEPRECATED)).isEqualTo(CourseService.StatusChangeResult.SUCCESS);
        // DEPRECATED -> ACTIVE is an allowed correction per docs/state-machines.md #9
        assertThat(courseService.changeStatus(courseId, CourseStatus.ACTIVE)).isEqualTo(CourseService.StatusChangeResult.SUCCESS);
        assertThat(courseService.changeStatus(courseId, CourseStatus.DEPRECATED)).isEqualTo(CourseService.StatusChangeResult.SUCCESS);
        assertThat(courseService.changeStatus(courseId, CourseStatus.RETIRED)).isEqualTo(CourseService.StatusChangeResult.SUCCESS);
        // RETIRED is terminal
        assertThat(courseService.changeStatus(courseId, CourseStatus.ACTIVE)).isEqualTo(CourseService.StatusChangeResult.INVALID_TRANSITION);
    }

    @Test
    void draftCannotSkipDirectlyToDeprecated() {
        var tenant = fixtures.provisionTenant("CRS6");
        UUID dept = fixtures.provisionDepartment(tenant, "CRS6D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "CRS6-101");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        assertThat(courseService.changeStatus(courseId, CourseStatus.DEPRECATED)).isEqualTo(CourseService.StatusChangeResult.INVALID_TRANSITION);
    }

    // --- Day 16 §faculty: get() is readable by any tenant member, not just admins ---

    @Test
    void facultyWithNoAdminPermissionCanStillReadACourseInTheirOwnTenant() {
        var tenant = fixtures.provisionTenant("CRS8");
        UUID dept = fixtures.provisionDepartment(tenant, "CRS8D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "CRS8-101");
        UUID facultyUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.FACULTY, "CRS8F");
        TestAuthentication.authenticateAs(facultyUserId);

        var result = courseService.get(courseId);

        assertThat(result).isPresent();
    }

    @Test
    void userWithNoRoleInTheTenantCannotReadTheCourse() {
        var tenant = fixtures.provisionTenant("CRS9A");
        UUID dept = fixtures.provisionDepartment(tenant, "CRS9AD");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "CRS9-101");
        var otherTenant = fixtures.provisionTenant("CRS9B");
        UUID outsiderUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.FACULTY, "CRS9BF");
        TestAuthentication.authenticateAs(outsiderUserId);

        assertThatThrownBy(() -> courseService.get(courseId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void paginationIsStable() {
        var tenant = fixtures.provisionTenant("CRS7");
        UUID dept = fixtures.provisionDepartment(tenant, "CRS7D");
        for (int i = 0; i < 5; i++) {
            fixtures.provisionCourse(tenant, dept, "CRS7-" + i);
        }
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var page1 = courseService.listForTenant(tenant.tenantId(), PageRequest.of(0, 2));
        var page2 = courseService.listForTenant(tenant.tenantId(), PageRequest.of(1, 2));

        assertThat(page1.getContent()).hasSize(2);
        assertThat(page2.getContent()).hasSize(2);
        assertThat(page1.getTotalElements()).isEqualTo(5);
        assertThat(page1.getContent()).noneMatch(c -> page2.getContent().contains(c));
    }
}
