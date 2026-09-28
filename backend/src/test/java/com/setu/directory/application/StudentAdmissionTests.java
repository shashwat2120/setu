package com.setu.directory.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.application.PersonService;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.Person;
import com.setu.identity.infrastructure.PersonRepository;
import com.setu.directory.application.DirectoryService;
import com.setu.directory.domain.StudentStatus;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.CollegeDirectoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 6 test matrix categories A (profile uniqueness/cross-tenant) and B (admission). */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class StudentAdmissionTests {

    @Autowired
    StudentProfileService studentProfileService;
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
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PersonRepository personRepository;
    @Autowired
    DirectoryService directoryService;
    @Autowired
    GuardianAccessService guardianAccessService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private UUID newTenant() {
        return collegeDirectoryService.provisionCollege("D" + UUID.randomUUID().toString().substring(0, 8),
            "Admission Test College", "Asia/Kolkata");
    }

    private UUID newCollegeAdmin(UUID tenantId) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("d-admin-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenantId, Instant.now(), null));
        return outcome.userId();
    }

    private UUID newPerson(String legalName) {
        return personService.createPerson(null, legalName, null, null, null, null, null, null);
    }

    // --- A: student profile creation + uniqueness ---
    @Test
    void validAdmissionCreatesActiveProfile() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        UUID person = newPerson("Alice Student");
        TestAuthentication.authenticateAs(admin);

        var result = studentProfileService.admitStudent(tenant, person, "ADM-001", "R-1", LocalDate.of(2026, 6, 1));

        assertThat(result).isInstanceOf(StudentProfileService.AdmitResult.Success.class);
        UUID studentId = ((StudentProfileService.AdmitResult.Success) result).studentId();
        var profile = studentProfileService.get(studentId).orElseThrow();
        assertThat(profile.status()).isEqualTo(StudentStatus.ACTIVE);
    }

    // --- A: a person cannot have multiple student profiles in the same tenant ---
    @Test
    void personCannotHaveTwoStudentProfilesInSameTenant() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        UUID person = newPerson("Bob Student");
        TestAuthentication.authenticateAs(admin);
        studentProfileService.admitStudent(tenant, person, "ADM-100", null, LocalDate.of(2026, 6, 1));

        var second = studentProfileService.admitStudent(tenant, person, "ADM-101", null, LocalDate.of(2026, 6, 1));

        assertThat(second).isInstanceOf(StudentProfileService.AdmitResult.PersonAlreadyHasProfileInTenant.class);
    }

    // --- B: duplicate admission number ---
    @Test
    void duplicateAdmissionNumberIsRejected() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        UUID personA = newPerson("Person A");
        UUID personB = newPerson("Person B");
        TestAuthentication.authenticateAs(admin);
        studentProfileService.admitStudent(tenant, personA, "ADM-SAME", null, LocalDate.of(2026, 6, 1));

        var result = studentProfileService.admitStudent(tenant, personB, "ADM-SAME", null, LocalDate.of(2026, 6, 1));

        assertThat(result).isInstanceOf(StudentProfileService.AdmitResult.DuplicateAdmissionNo.class);
    }

    // --- B / A: cross-tenant / wrong tenant — an admin of tenant A cannot admit into tenant B ---
    @Test
    void collegeAdminCannotAdmitStudentIntoAnotherTenant() {
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();
        UUID adminOfA = newCollegeAdmin(tenantA);
        UUID person = newPerson("Cross Tenant Student");
        TestAuthentication.authenticateAs(adminOfA);

        assertThatThrownBy(() -> studentProfileService.admitStudent(tenantB, person, "ADM-X", null, LocalDate.of(2026, 6, 1)))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- B: invalid state transition ---
    @Test
    void invalidStateTransitionIsRejected() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        UUID person = newPerson("Grad Student");
        TestAuthentication.authenticateAs(admin);
        UUID studentId = ((StudentProfileService.AdmitResult.Success)
            studentProfileService.admitStudent(tenant, person, "ADM-200", null, LocalDate.of(2026, 6, 1))).studentId();
        studentProfileService.changeStatus(studentId, StudentStatus.WITHDRAWN, "left"); // WITHDRAWN is terminal

        var result = studentProfileService.changeStatus(studentId, StudentStatus.ACTIVE, "reinstate");

        assertThat(result).isEqualTo(StudentProfileService.StatusChangeResult.INVALID_TRANSITION);
    }

    // --- B: StudentAdmitted domain event written to the outbox (not published to Kafka — Day 6 explicit scope) ---
    @Test
    void studentAdmittedEventIsWrittenToOutbox() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        UUID person = newPerson("Event Student");
        TestAuthentication.authenticateAs(admin);

        UUID studentId = ((StudentProfileService.AdmitResult.Success)
            studentProfileService.admitStudent(tenant, person, "ADM-300", null, LocalDate.of(2026, 6, 1))).studentId();

        // No explicit flush needed: the class is no longer @Transactional (Sprint 5D hardening — see this file's
        // other tests' note), so admitStudent()'s own StudentProfileInsertGuard REQUIRES_NEW transaction has
        // already committed the profile and the outbox event by the time this call returns.
        Integer outboxCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.outbox_events WHERE event_type = 'StudentAdmitted' AND aggregate_id = ?",
            Integer.class, studentId);
        assertThat(outboxCount).isEqualTo(1);

        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.audit_logs WHERE action = 'STUDENT_ADMITTED' AND resource_id = ?",
            Integer.class, studentId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void unauthorizedRoleCannotAdmitStudents() {
        UUID tenant = newTenant();
        UUID student = newPerson("Some Student");
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("plain-student-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.STUDENT).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenant, Instant.now(), null));
        TestAuthentication.authenticateAs(outcome.userId());

        assertThatThrownBy(() -> studentProfileService.admitStudent(tenant, student, "ADM-400", null, LocalDate.of(2026, 6, 1)))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §admin: listForTenant ---

    @Test
    void collegeAdminCanListStudentsInTheirOwnTenant() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        studentProfileService.admitStudent(tenant, newPerson("List Student One"), "ADM-L1", null, LocalDate.of(2026, 6, 1));
        studentProfileService.admitStudent(tenant, newPerson("List Student Two"), "ADM-L2", null, LocalDate.of(2026, 6, 1));

        var page = studentProfileService.listForTenant(tenant, org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void listForTenantNeverIncludesAnotherTenantsStudents() {
        UUID tenantA = newTenant();
        UUID adminA = newCollegeAdmin(tenantA);
        TestAuthentication.authenticateAs(adminA);
        studentProfileService.admitStudent(tenantA, newPerson("Tenant A Student"), "ADM-A1", null, LocalDate.of(2026, 6, 1));
        TestAuthentication.clear();

        UUID tenantB = newTenant();
        UUID adminB = newCollegeAdmin(tenantB);
        TestAuthentication.authenticateAs(adminB);
        studentProfileService.admitStudent(tenantB, newPerson("Tenant B Student"), "ADM-B1", null, LocalDate.of(2026, 6, 1));

        var page = studentProfileService.listForTenant(tenantB, org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).admissionNo()).isEqualTo("ADM-B1");
    }

    // --- Day 16 §faculty: get() is readable by any tenant member, not just admins ---

    @Test
    void facultyWithNoAdminPermissionCanStillReadAStudentProfileInTheirOwnTenant() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID studentId = ((StudentProfileService.AdmitResult.Success)
            studentProfileService.admitStudent(tenant, newPerson("Readable Student"), "ADM-READ1", null, LocalDate.of(2026, 6, 1))).studentId();
        TestAuthentication.clear();

        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("faculty-reader-" + UUID.randomUUID() + "@example.com");
        UUID facultyRoleId = roleRepository.findByCode(RoleCode.FACULTY).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), facultyRoleId, tenant, Instant.now(), null));
        TestAuthentication.authenticateAs(outcome.userId());

        var result = studentProfileService.get(studentId);

        assertThat(result).isPresent();
    }

    @Test
    void userWithNoRoleInTheTenantCannotReadTheStudentProfile() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID studentId = ((StudentProfileService.AdmitResult.Success)
            studentProfileService.admitStudent(tenant, newPerson("Protected Student"), "ADM-PROT1", null, LocalDate.of(2026, 6, 1))).studentId();
        TestAuthentication.clear();

        UUID otherTenant = newTenant();
        UUID outsiderAdmin = newCollegeAdmin(otherTenant);
        TestAuthentication.authenticateAs(outsiderAdmin);

        assertThatThrownBy(() -> studentProfileService.get(studentId)).isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §guardian: the roster-lookup relaxation above must never extend to a bare GUARDIAN role ---

    private UUID newGuardian(UUID tenant) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("guardian-reader-" + UUID.randomUUID() + "@example.com");
        UUID guardianUserId = outcome.userId();
        UUID guardianRoleId = roleRepository.findByCode(RoleCode.GUARDIAN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), guardianUserId, guardianRoleId, tenant, Instant.now(), null));
        return guardianUserId;
    }

    @Test
    void guardianWithMereTenantMembershipCannotReadAnUnrelatedStudentsProfile() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID studentId = ((StudentProfileService.AdmitResult.Success)
            studentProfileService.admitStudent(tenant, newPerson("Unrelated Student"), "ADM-GUARD1", null, LocalDate.of(2026, 6, 1))).studentId();
        TestAuthentication.clear();

        UUID guardianUserId = newGuardian(tenant); // active GUARDIAN role in the SAME tenant, but no relationship, no grant
        TestAuthentication.authenticateAs(guardianUserId);

        assertThatThrownBy(() -> studentProfileService.get(studentId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void guardianWithActiveViewAcademicStatusDelegationCanReadTheStudentsProfile() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID studentId = ((StudentProfileService.AdmitResult.Success)
            studentProfileService.admitStudent(tenant, newPerson("Delegated Student"), "ADM-GUARD2", null, LocalDate.of(2026, 6, 1))).studentId();

        UUID guardianUserId = newGuardian(tenant);
        Person guardianPerson = Person.withLegalName(UUID.randomUUID(), guardianUserId, "Test Guardian", Instant.now());
        personRepository.save(guardianPerson);
        UUID guardianId = directoryService.provisionGuardian(tenant, guardianPerson.id(), guardianUserId);
        guardianAccessService.grantAccess(studentId, guardianId, null, java.util.Set.of("VIEW_ACADEMIC_STATUS"));
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(guardianUserId);

        var result = studentProfileService.get(studentId);

        assertThat(result).isPresent();
    }

    @Test
    void unauthorizedRoleCannotListStudents() {
        UUID tenant = newTenant();
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("plain-lister-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.STUDENT).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenant, Instant.now(), null));
        TestAuthentication.authenticateAs(outcome.userId());

        assertThatThrownBy(() -> studentProfileService.listForTenant(tenant, org.springframework.data.domain.PageRequest.of(0, 20)))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §placement: placement officers need a roster view to resolve applications/placements to
    // student names, but must never gain it through VIEW_PROFILE (also held by plain STUDENT/FACULTY) — see
    // listForTenant's javadoc for why MANAGE_CAREER_LISTINGS is the correct, narrower permission instead.

    @Test
    void placementOfficerCanListStudentsInTheirOwnTenant() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        studentProfileService.admitStudent(tenant, newPerson("Roster Student"), "ADM-ROSTER1", null, LocalDate.of(2026, 6, 1));
        TestAuthentication.clear();

        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("placement-lister-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.PLACEMENT_OFFICER).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenant, Instant.now(), null));
        TestAuthentication.authenticateAs(outcome.userId());

        var page = studentProfileService.listForTenant(tenant, org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
    }

}
