package com.setu.directory.application;

import com.setu.directory.domain.FacultyStatus;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.PersonService;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.CollegeDirectoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 6 test matrix category C: faculty. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class FacultyProfileTests {

    @Autowired
    FacultyProfileService facultyProfileService;
    @Autowired
    PersonService personService;
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

    private UUID newTenant() {
        return collegeDirectoryService.provisionCollege("F" + UUID.randomUUID().toString().substring(0, 8),
            "Faculty Test College", "Asia/Kolkata");
    }

    private UUID newCollegeAdmin(UUID tenantId) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("f-admin-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenantId, Instant.now(), null));
        return outcome.userId();
    }

    private UUID newPerson(String name) {
        return personService.createPerson(null, name, null, null, null, null, null, null);
    }

    @Test
    void validFacultyCreation() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        UUID person = newPerson("Dr. Faculty");
        TestAuthentication.authenticateAs(admin);

        var result = facultyProfileService.create(tenant, person, "EMP-001", "Professor", LocalDate.of(2020, 1, 1));

        assertThat(result).isInstanceOf(FacultyProfileService.CreateResult.Success.class);
    }

    @Test
    void duplicateEmployeeNumberIsRejected() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        UUID personA = newPerson("Faculty A");
        UUID personB = newPerson("Faculty B");
        TestAuthentication.authenticateAs(admin);
        facultyProfileService.create(tenant, personA, "EMP-SAME", null, LocalDate.of(2020, 1, 1));

        var result = facultyProfileService.create(tenant, personB, "EMP-SAME", null, LocalDate.of(2020, 1, 1));

        assertThat(result).isInstanceOf(FacultyProfileService.CreateResult.DuplicateEmployeeNo.class);
    }

    @Test
    void wrongTenantIsDenied() {
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();
        UUID adminOfA = newCollegeAdmin(tenantA);
        UUID person = newPerson("Cross Tenant Faculty");
        TestAuthentication.authenticateAs(adminOfA);

        assertThatThrownBy(() -> facultyProfileService.create(tenantB, person, "EMP-X", null, LocalDate.of(2020, 1, 1)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invalidStatusTransitionIsRejected() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        UUID person = newPerson("Terminated Faculty");
        TestAuthentication.authenticateAs(admin);
        UUID facultyId = ((FacultyProfileService.CreateResult.Success)
            facultyProfileService.create(tenant, person, "EMP-200", null, LocalDate.of(2020, 1, 1))).facultyId();
        facultyProfileService.changeStatus(facultyId, FacultyStatus.TERMINATED);

        var result = facultyProfileService.changeStatus(facultyId, FacultyStatus.ACTIVE);

        assertThat(result).isEqualTo(FacultyProfileService.StatusChangeResult.INVALID_TRANSITION);
    }

    // --- Day 16 §admin: listForTenant ---

    @Test
    void collegeAdminCanListFacultyInTheirOwnTenant() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        facultyProfileService.create(tenant, newPerson("List Faculty One"), "EMP-L1", null, LocalDate.of(2020, 1, 1));
        facultyProfileService.create(tenant, newPerson("List Faculty Two"), "EMP-L2", null, LocalDate.of(2020, 1, 1));

        var page = facultyProfileService.listForTenant(tenant, org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void listForTenantNeverIncludesAnotherTenantsFaculty() {
        UUID tenantA = newTenant();
        UUID adminA = newCollegeAdmin(tenantA);
        TestAuthentication.authenticateAs(adminA);
        facultyProfileService.create(tenantA, newPerson("Tenant A Faculty"), "EMP-A1", null, LocalDate.of(2020, 1, 1));
        TestAuthentication.clear();

        UUID tenantB = newTenant();
        UUID adminB = newCollegeAdmin(tenantB);
        TestAuthentication.authenticateAs(adminB);
        facultyProfileService.create(tenantB, newPerson("Tenant B Faculty"), "EMP-B1", null, LocalDate.of(2020, 1, 1));

        var page = facultyProfileService.listForTenant(tenantB, org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).employeeNo()).isEqualTo("EMP-B1");
    }

    @Test
    void unauthorizedRoleCannotListFaculty() {
        UUID tenant = newTenant();
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("plain-flister-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.STUDENT).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenant, Instant.now(), null));
        TestAuthentication.authenticateAs(outcome.userId());

        assertThatThrownBy(() -> facultyProfileService.listForTenant(tenant, org.springframework.data.domain.PageRequest.of(0, 20)))
            .isInstanceOf(AccessDeniedException.class);
    }
}
