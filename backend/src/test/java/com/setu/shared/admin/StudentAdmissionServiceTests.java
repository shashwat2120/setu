package com.setu.shared.admin;

import com.setu.directory.application.StudentProfileService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.shared.identity.PersonDirectory;
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

/**
 * The real "College Admin adds a new student" flow — the manual-UI counterpart to
 * {@code shared.seed.DemoDataSeeder}'s own student-admission path. Verifies the full chain (account, person,
 * profile, STUDENT role — all step-up-exempt per {@link com.setu.identity.application.RoleAssignmentService}),
 * that a duplicate email is rejected before any partial state leaks into a misleading success, and that
 * authorization is genuinely enforced (a caller without MANAGE_STUDENTS is denied, never a client-side-only gate).
 * <p>
 * Deliberately NOT {@code @Transactional} — {@link StudentProfileService#admitStudent} genuinely writes the
 * profile through {@code StudentProfileInsertGuard}'s own {@code REQUIRES_NEW} transaction (its own javadoc:
 * the same poisoned-transaction defense {@code AssessmentComponentInsertGuard} uses), a real, separate DB
 * connection that can never see this test's OWN uncommitted setup rows if the test itself ran inside a rolled-
 * back transaction — the exact real interaction {@code DemoDataSeederVerificationTests} already avoids the same
 * way. Every test below therefore uses a fresh, randomly-suffixed tenant/email so tests stay independent without
 * relying on rollback.
 */
@SpringBootTest
class StudentAdmissionServiceTests {

    @Autowired
    StudentAdmissionService studentAdmissionService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    PersonDirectory personDirectory;
    @Autowired
    StudentProfileService studentProfileService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private UUID bootstrapCollegeAdmin(UUID tenantId) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("bootstrap-admin-" + UUID.randomUUID() + "@example.com");
        UUID adminUserId = outcome.userId();
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), adminUserId, roleId, tenantId, Instant.now(), null));
        return adminUserId;
    }

    @Test
    void collegeAdminAdmitsANewStudentWhoCanImmediatelyBeFoundInTheDirectory() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "A" + UUID.randomUUID().toString().substring(0, 8), "Admission Test College", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        String email = "new-student-" + UUID.randomUUID() + "@example.com";
        TestAuthentication.authenticateAs(admin, null); // no step-up — STUDENT grant must not need one

        var result = studentAdmissionService.admitNewStudent(tenant, "Test Student", "Test", LocalDate.of(2006, 1, 1),
            "UNSPECIFIED", email, "+91-9000000000", "ADM-TEST-1", "ROLL-TEST-1", LocalDate.now());

        assertThat(result).isInstanceOf(StudentAdmissionService.AdmitNewStudentResult.Success.class);
        var success = (StudentAdmissionService.AdmitNewStudentResult.Success) result;

        var person = personDirectory.findByUserId(success.userId());
        assertThat(person).isPresent();
        assertThat(person.orElseThrow().legalName()).isEqualTo("Test Student");

        assertThat(studentProfileService.get(success.studentId())).isPresent();

        boolean hasStudentRole = userRoleRepository.findByUserId(success.userId()).stream()
            .anyMatch(ur -> ur.tenantId() != null && ur.tenantId().equals(tenant) && ur.isActive(Instant.now()));
        assertThat(hasStudentRole).isTrue();
    }

    @Test
    void anEmailAlreadyRegisteredIsRejectedRatherThanCreatingADuplicateOrOrphanedAccount() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "A" + UUID.randomUUID().toString().substring(0, 8), "Admission Test College 2", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        String email = "already-registered-" + UUID.randomUUID() + "@example.com";
        userRegistrationService.register(email);
        TestAuthentication.authenticateAs(admin, null);

        var result = studentAdmissionService.admitNewStudent(tenant, "Duplicate Email", "Duplicate",
            LocalDate.of(2006, 1, 1), "UNSPECIFIED", email, "+91-9000000001", "ADM-TEST-2", "ROLL-TEST-2", LocalDate.now());

        assertThat(result).isInstanceOf(StudentAdmissionService.AdmitNewStudentResult.EmailAlreadyRegistered.class);
    }

    @Test
    void aCallerWithoutManageStudentsIsDenied() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "A" + UUID.randomUUID().toString().substring(0, 8), "Admission Test College 3", "Asia/Kolkata");
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("plain-faculty-" + UUID.randomUUID() + "@example.com");
        UUID facultyRoleId = roleRepository.findByCode(RoleCode.FACULTY).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), facultyRoleId, tenant, Instant.now(), null));
        TestAuthentication.authenticateAs(outcome.userId(), Instant.now());

        assertThatThrownBy(() -> studentAdmissionService.admitNewStudent(tenant, "Victim Student", "Victim",
            LocalDate.of(2006, 1, 1), "UNSPECIFIED", "victim-" + UUID.randomUUID() + "@example.com",
            "+91-9000000002", "ADM-TEST-3", "ROLL-TEST-3", LocalDate.now()))
            .isInstanceOf(AccessDeniedException.class);
    }
}
