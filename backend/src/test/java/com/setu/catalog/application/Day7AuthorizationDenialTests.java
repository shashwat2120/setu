package com.setu.catalog.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 7 §13 role-based DENY scenarios that don't fit naturally inside a
 * single entity's own test class: a student or guardian attempting to
 * mutate academic catalog data. Cross-tenant admin scenarios are covered
 * per-entity (CourseTests, CourseSectionTests, FacultyAssignmentTests,
 * BuildingRoomTests, MeetingPatternTests).
 */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class Day7AuthorizationDenialTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    CourseService courseService;
    @Autowired
    BuildingService buildingService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void studentCannotCreateCourse() {
        var tenant = fixtures.provisionTenant("AUTH1");
        UUID dept = fixtures.provisionDepartment(tenant, "AUTH1D");
        UUID studentUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.STUDENT, "AUTH1-STU");
        TestAuthentication.authenticateAs(studentUserId);

        assertThatThrownBy(() -> courseService.create(dept, "AUTH1-101", "Student Attempt", new BigDecimal("3.0"), null, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void guardianCannotCreateCourse() {
        var tenant = fixtures.provisionTenant("AUTH2");
        UUID dept = fixtures.provisionDepartment(tenant, "AUTH2D");
        UUID guardianUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.GUARDIAN, "AUTH2-GRD");
        TestAuthentication.authenticateAs(guardianUserId);

        assertThatThrownBy(() -> courseService.create(dept, "AUTH2-101", "Guardian Attempt", new BigDecimal("3.0"), null, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void facultyCannotCreateBuildingWithoutManageFacilitiesPermission() {
        var tenant = fixtures.provisionTenant("AUTH3");
        UUID facultyUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.FACULTY, "AUTH3-FAC");
        TestAuthentication.authenticateAs(facultyUserId);

        assertThatThrownBy(() -> buildingService.create(tenant.tenantId(), "AUTH3-B1", "Unauthorized Building"))
            .isInstanceOf(AccessDeniedException.class);
    }
}
