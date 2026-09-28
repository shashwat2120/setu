package com.setu.catalog.application;

import com.setu.catalog.domain.ProgramStatus;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.AcademicOrganizationService;
import com.setu.institution.application.CampusService;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.institution.application.DepartmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 6 test matrix category D: program. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class ProgramTests {

    @Autowired
    ProgramService programService;
    @Autowired
    CampusService campusService;
    @Autowired
    AcademicOrganizationService organizationService;
    @Autowired
    DepartmentService departmentService;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    RoleRepository roleRepository;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record TenantWithDepartment(UUID tenantId, UUID adminUserId, UUID departmentId) {
    }

    private TenantWithDepartment newTenantWithDepartment() {
        UUID tenant = collegeDirectoryService.provisionCollege("P" + UUID.randomUUID().toString().substring(0, 8),
            "Program Test College", "Asia/Kolkata");
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("p-admin-" + UUID.randomUUID() + "@example.com");
        UUID admin = outcome.userId();
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), admin, roleId, tenant, Instant.now(), null));

        TestAuthentication.authenticateAs(admin);
        var campus = (CampusService.CreateResult.Success) campusService.create(tenant, "C1", "Main Campus");
        var org = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), null, "Faculty of Science", "FACULTY");
        var dept = (DepartmentService.CreateResult.Success) departmentService.create(org.organizationId(), "CS", "Computer Science");
        TestAuthentication.clear();

        return new TenantWithDepartment(tenant, admin, dept.departmentId());
    }

    @Test
    void validProgramCreation() {
        var t = newTenantWithDepartment();
        TestAuthentication.authenticateAs(t.adminUserId());

        var result = programService.create(t.departmentId(), "BTECH-CS", "B.Tech Computer Science", "BACHELORS", 8, new BigDecimal("160.00"));

        assertThat(result).isInstanceOf(ProgramService.CreateResult.Success.class);
    }

    @Test
    void duplicateProgramCodeIsRejected() {
        var t = newTenantWithDepartment();
        TestAuthentication.authenticateAs(t.adminUserId());
        programService.create(t.departmentId(), "DUP-CODE", "Program One", null, null, null);

        var result = programService.create(t.departmentId(), "DUP-CODE", "Program Two", null, null, null);

        assertThat(result).isInstanceOf(ProgramService.CreateResult.CodeAlreadyInUse.class);
    }

    // --- department/program tenant mismatch: an admin of tenant A cannot attach a program to tenant B's department ---
    @Test
    void programCannotAttachToCrossTenantDepartment() {
        var tenantA = newTenantWithDepartment();
        var tenantB = newTenantWithDepartment();
        TestAuthentication.authenticateAs(tenantA.adminUserId());

        // adminA tries to create a program against department B — assertCan will
        // reject because adminA has no role in tenantB (the department's real tenant).
        assertThatThrownBy(() -> programService.create(tenantB.departmentId(), "X", "Cross Tenant Program", null, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invalidProgramStatusTransitionIsRejected() {
        var t = newTenantWithDepartment();
        TestAuthentication.authenticateAs(t.adminUserId());
        UUID programId = ((ProgramService.CreateResult.Success)
            programService.create(t.departmentId(), "TRANS-1", "Transition Program", null, null, null)).programId();
        programService.changeStatus(programId, ProgramStatus.INACTIVE);

        // INACTIVE -> INACTIVE is not a valid transition (must go to ACTIVE first).
        var result = programService.changeStatus(programId, ProgramStatus.INACTIVE);

        assertThat(result).isEqualTo(ProgramService.StatusChangeResult.INVALID_TRANSITION);
    }

    @Test
    void invalidProgramDataIsRejected() {
        var t = newTenantWithDepartment();
        TestAuthentication.authenticateAs(t.adminUserId());

        var result = programService.create(t.departmentId(), "BAD-1", "Bad Program", null, -1, null);

        assertThat(result).isInstanceOf(ProgramService.CreateResult.InvalidProgramData.class);
    }
}
