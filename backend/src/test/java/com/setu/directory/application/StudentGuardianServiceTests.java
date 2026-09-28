package com.setu.directory.application;

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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Day 6 test matrix category F: guardian relationship. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class StudentGuardianServiceTests {

    @Autowired
    StudentGuardianService studentGuardianService;
    @Autowired
    StudentProfileService studentProfileService;
    @Autowired
    GuardianProfileService guardianProfileService;
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
        return collegeDirectoryService.provisionCollege("SG" + UUID.randomUUID().toString().substring(0, 7),
            "Student Guardian Test College", "Asia/Kolkata");
    }

    private UUID newCollegeAdmin(UUID tenantId) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("sg-admin-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenantId, Instant.now(), null));
        return outcome.userId();
    }

    private UUID newStudent(UUID tenantId, String admissionNo) {
        UUID person = personService.createPerson(null, "Student " + admissionNo, null, null, null, null, null, null);
        return ((StudentProfileService.AdmitResult.Success)
            studentProfileService.admitStudent(tenantId, person, admissionNo, null, LocalDate.of(2026, 6, 1))).studentId();
    }

    private UUID newGuardian(UUID tenantId, String name) {
        UUID person = personService.createPerson(null, name, null, null, null, null, null, null);
        return ((GuardianProfileService.CreateResult.Success)
            guardianProfileService.create(tenantId, person, null, null, null)).guardianId();
    }

    @Test
    void validRelationship() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID student = newStudent(tenant, "SG-001");
        UUID guardian = newGuardian(tenant, "Parent One");

        var result = studentGuardianService.link(student, guardian, "FATHER", true, true);

        assertThat(result).isInstanceOf(StudentGuardianService.LinkResult.Success.class);
    }

    @Test
    void multipleGuardiansForOneStudent() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID student = newStudent(tenant, "SG-100");
        UUID father = newGuardian(tenant, "Father");
        UUID mother = newGuardian(tenant, "Mother");

        studentGuardianService.link(student, father, "FATHER", true, false);
        studentGuardianService.link(student, mother, "MOTHER", false, true);

        var guardians = studentGuardianService.guardiansForStudent(student);
        assertThat(guardians).hasSize(2);
    }

    @Test
    void oneGuardianForMultipleStudents() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID studentA = newStudent(tenant, "SG-200");
        UUID studentB = newStudent(tenant, "SG-201");
        UUID guardian = newGuardian(tenant, "Busy Parent");

        studentGuardianService.link(studentA, guardian, "PARENT", true, false);
        studentGuardianService.link(studentB, guardian, "PARENT", true, false);

        var students = studentGuardianService.studentsForGuardian(guardian);
        assertThat(students).hasSize(2);
    }

    @Test
    void duplicateRelationshipIsRejected() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID student = newStudent(tenant, "SG-300");
        UUID guardian = newGuardian(tenant, "Repeat Parent");
        studentGuardianService.link(student, guardian, "FATHER", true, false);

        var result = studentGuardianService.link(student, guardian, "FATHER", false, false);

        assertThat(result).isInstanceOf(StudentGuardianService.LinkResult.DuplicateRelationship.class);
    }

    @Test
    void crossTenantRelationshipIsRejected() {
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();
        UUID adminA = newCollegeAdmin(tenantA);
        UUID adminB = newCollegeAdmin(tenantB);

        TestAuthentication.authenticateAs(adminA);
        UUID studentInA = newStudent(tenantA, "SG-400");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(adminB);
        UUID guardianInB = newGuardian(tenantB, "Other Tenant Parent");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(adminA);
        var result = studentGuardianService.link(studentInA, guardianInB, "FATHER", true, false);

        assertThat(result).isInstanceOf(StudentGuardianService.LinkResult.TenantMismatch.class);
    }

    @Test
    void settingNewPrimaryClearsThePreviousOne() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID student = newStudent(tenant, "SG-500");
        UUID guardianA = newGuardian(tenant, "First Primary");
        UUID guardianB = newGuardian(tenant, "New Primary");
        studentGuardianService.link(student, guardianA, "FATHER", true, false);

        studentGuardianService.link(student, guardianB, "MOTHER", true, false);

        var links = studentGuardianService.guardiansForStudent(student);
        assertThat(links.stream().filter(com.setu.directory.domain.StudentGuardian::isPrimary)).hasSize(1);
    }
}
