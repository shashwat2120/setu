package com.setu.enrollment.application;

import com.setu.directory.application.FacultyProfileService;
import com.setu.enrollment.domain.FacultyAssignmentRole;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.shared.identity.PersonDirectory;
import com.setu.testsupport.Day7TestFixtures;
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

/** Day 7 §16 FACULTY ASSIGNMENTS test matrix. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class FacultyAssignmentTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    FacultyAssignmentService facultyAssignmentService;
    @Autowired
    FacultyProfileService facultyProfileService;
    @Autowired
    PersonDirectory personDirectory;
    @Autowired
    EnrollmentService enrollmentService;

    private record FacultyWithUser(UUID facultyId, UUID userId) {
    }

    private FacultyWithUser provisionFacultyWithUser(Day7TestFixtures.Tenant tenant, String prefix) {
        UUID facultyUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.FACULTY, prefix);
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            UUID personId = personDirectory.createPerson(facultyUserId, prefix + " Legal Name", null, null, null,
                prefix + "@example.com", null, null);
            var result = (FacultyProfileService.CreateResult.Success)
                facultyProfileService.create(tenant.tenantId(), personId, prefix + "-EMP", "Lecturer", LocalDate.of(2020, 1, 1));
            return new FacultyWithUser(result.facultyId(), facultyUserId);
        } finally {
            TestAuthentication.clear();
        }
    }

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Fixture(Day7TestFixtures.Tenant tenant, UUID sectionId, UUID facultyId) {
    }

    private Fixture setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = fixtures.provisionPeriod(tenant, prefix + "P");
        UUID sectionId = fixtures.provisionSection(tenant, courseId, periodId, "S1", 30, null);
        UUID facultyId = fixtures.provisionFaculty(tenant, prefix + "F");
        return new Fixture(tenant, sectionId, facultyId);
    }

    @Test
    void validAssignment() {
        var f = setUp("FA1");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = facultyAssignmentService.assign(f.sectionId, f.facultyId, FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);

        assertThat(result).isInstanceOf(FacultyAssignmentService.AssignResult.Success.class);
    }

    @Test
    void missingFacultyIsRejected() {
        var f = setUp("FA2");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = facultyAssignmentService.assign(f.sectionId, UUID.randomUUID(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);

        assertThat(result).isInstanceOf(FacultyAssignmentService.AssignResult.FacultyNotFound.class);
    }

    @Test
    void crossTenantFacultyIsRejected() {
        var f = setUp("FA3A");
        var otherTenant = fixtures.provisionTenant("FA3B");
        UUID otherFaculty = fixtures.provisionFaculty(otherTenant, "FA3BF");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = facultyAssignmentService.assign(f.sectionId, otherFaculty, FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);

        assertThat(result).isInstanceOf(FacultyAssignmentService.AssignResult.TenantMismatch.class);
    }

    @Test
    void invalidAllocationIsRejected() {
        var f = setUp("FA4");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = facultyAssignmentService.assign(f.sectionId, f.facultyId, FacultyAssignmentRole.CO_INSTRUCTOR,
            new BigDecimal("150"), LocalDate.of(2025, 1, 1), null);

        assertThat(result).isInstanceOf(FacultyAssignmentService.AssignResult.InvalidAssignmentData.class);
    }

    @Test
    void invalidDatesAreRejected() {
        var f = setUp("FA5");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = facultyAssignmentService.assign(f.sectionId, f.facultyId, FacultyAssignmentRole.CO_INSTRUCTOR,
            new BigDecimal("50"), LocalDate.of(2025, 6, 1), LocalDate.of(2025, 1, 1));

        assertThat(result).isInstanceOf(FacultyAssignmentService.AssignResult.InvalidAssignmentData.class);
    }

    @Test
    void duplicatePrimaryAssignmentIsRejected() {
        var f = setUp("FA6");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        facultyAssignmentService.assign(f.sectionId, f.facultyId, FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);
        UUID secondFaculty = fixtures.provisionFaculty(f.tenant, "FA6F2");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = facultyAssignmentService.assign(f.sectionId, secondFaculty, FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);

        assertThat(result).isInstanceOf(FacultyAssignmentService.AssignResult.PrimaryAlreadyAssigned.class);
    }

    @Test
    void historyIsPreservedByEndingRatherThanDeleting() {
        var f = setUp("FA7");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        UUID assignmentId = ((FacultyAssignmentService.AssignResult.Success)
            facultyAssignmentService.assign(f.sectionId, f.facultyId, FacultyAssignmentRole.PRIMARY,
                new BigDecimal("100"), LocalDate.of(2025, 1, 1), null)).assignmentId();

        facultyAssignmentService.end(assignmentId, LocalDate.of(2025, 6, 1));

        var ended = facultyAssignmentService.get(assignmentId).orElseThrow();
        assertThat(ended.isActive()).isFalse();
        assertThat(ended.endsAt()).isEqualTo(LocalDate.of(2025, 6, 1));
        // A new PRIMARY assignment may now be made — the row still exists (history preserved), just no longer active.
        UUID secondFaculty = fixtures.provisionFaculty(f.tenant, "FA7F2");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        var reassign = facultyAssignmentService.assign(f.sectionId, secondFaculty, FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 6, 2), null);
        assertThat(reassign).isInstanceOf(FacultyAssignmentService.AssignResult.Success.class);
    }

    @Test
    void crossTenantSectionAssignmentIsDenied() {
        var f = setUp("FA8A");
        var otherTenant = fixtures.provisionTenant("FA8B");
        TestAuthentication.authenticateAs(otherTenant.adminUserId());

        assertThatThrownBy(() -> facultyAssignmentService.assign(f.sectionId, f.facultyId, FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null)).isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §faculty: listForFaculty ---

    @Test
    void facultyMemberCanListTheirOwnAssignments() {
        var tenant = fixtures.provisionTenant("FA9");
        UUID dept = fixtures.provisionDepartment(tenant, "FA9D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "FA9C");
        UUID periodId = fixtures.provisionPeriod(tenant, "FA9P");
        UUID sectionId = fixtures.provisionSection(tenant, courseId, periodId, "S1", 30, null);
        var faculty = provisionFacultyWithUser(tenant, "FA9F");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        facultyAssignmentService.assign(sectionId, faculty.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(faculty.userId());
        var result = facultyAssignmentService.listForFaculty(faculty.facultyId(), tenant.tenantId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sectionId()).isEqualTo(sectionId);
    }

    @Test
    void facultyMemberCannotListAnotherFacultyMembersAssignments() {
        var tenant = fixtures.provisionTenant("FA10");
        UUID dept = fixtures.provisionDepartment(tenant, "FA10D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "FA10C");
        UUID periodId = fixtures.provisionPeriod(tenant, "FA10P");
        UUID sectionId = fixtures.provisionSection(tenant, courseId, periodId, "S1", 30, null);
        var facultyA = provisionFacultyWithUser(tenant, "FA10FA");
        var facultyB = provisionFacultyWithUser(tenant, "FA10FB");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        facultyAssignmentService.assign(sectionId, facultyA.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(facultyB.userId());
        assertThatThrownBy(() -> facultyAssignmentService.listForFaculty(facultyA.facultyId(), tenant.tenantId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §faculty: EnrollmentService.listForSection assigned-faculty bypass ---

    @Test
    void assignedFacultyCanListEnrollmentsForTheirOwnSection() {
        var tenant = fixtures.provisionTenant("FA12");
        UUID dept = fixtures.provisionDepartment(tenant, "FA12D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "FA12C");
        UUID periodId = fixtures.provisionPeriod(tenant, "FA12P");
        UUID sectionId = fixtures.provisionSection(tenant, courseId, periodId, "S1", 30, null);
        var faculty = provisionFacultyWithUser(tenant, "FA12F");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        facultyAssignmentService.assign(sectionId, faculty.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(faculty.userId());
        var page = enrollmentService.listForSection(sectionId, tenant.tenantId(), org.springframework.data.domain.Pageable.unpaged());

        assertThat(page).isNotNull();
    }

    @Test
    void unassignedFacultyCannotListEnrollmentsForAnotherFacultysSection() {
        var tenant = fixtures.provisionTenant("FA13");
        UUID dept = fixtures.provisionDepartment(tenant, "FA13D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "FA13C");
        UUID periodId = fixtures.provisionPeriod(tenant, "FA13P");
        UUID sectionId = fixtures.provisionSection(tenant, courseId, periodId, "S1", 30, null);
        var facultyA = provisionFacultyWithUser(tenant, "FA13FA");
        var facultyB = provisionFacultyWithUser(tenant, "FA13FB");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        facultyAssignmentService.assign(sectionId, facultyA.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(facultyB.userId());
        assertThatThrownBy(() -> enrollmentService.listForSection(sectionId, tenant.tenantId(), org.springframework.data.domain.Pageable.unpaged()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void collegeAdminCanListAnyFacultyMembersAssignments() {
        var tenant = fixtures.provisionTenant("FA11");
        UUID dept = fixtures.provisionDepartment(tenant, "FA11D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "FA11C");
        UUID periodId = fixtures.provisionPeriod(tenant, "FA11P");
        UUID sectionId = fixtures.provisionSection(tenant, courseId, periodId, "S1", 30, null);
        var faculty = provisionFacultyWithUser(tenant, "FA11F");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        facultyAssignmentService.assign(sectionId, faculty.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);

        var result = facultyAssignmentService.listForFaculty(faculty.facultyId(), tenant.tenantId());

        assertThat(result).hasSize(1);
    }
}
