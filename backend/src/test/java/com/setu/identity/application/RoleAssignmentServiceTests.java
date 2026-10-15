package com.setu.identity.application;

import com.setu.identity.TestAuthentication;
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
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Role changes are the Day 4 instruction §8's own named example of a step-up-gated sensitive operation for
 * ELEVATED roles, and §9 requires every role change audited regardless of step-up. Later product decision
 * (see {@link RoleAssignmentService#ELEVATED_ROLES}'s own javadoc): STUDENT/GUARDIAN grants and revocations are
 * exempt from step-up — routine admission/onboarding, not privilege escalation — while
 * SUPER_ADMIN/COLLEGE_ADMIN/FACULTY/PLACEMENT_OFFICER still require it. Bootstrapping the first COLLEGE_ADMIN in
 * these tests goes directly through UserRoleRepository, not RoleAssignmentService — a brand-new platform
 * genuinely has no SUPER_ADMIN yet to grant the first role through this service; that first grant is necessarily
 * an operational/migration-seed step, same as any real RBAC system's bootstrap problem, not something this
 * service can also be the gatekeeper for.
 */
@SpringBootTest
@Transactional
class RoleAssignmentServiceTests {

    @Autowired
    RoleAssignmentService roleAssignmentService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager entityManager;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private UUID newUser(String prefix) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register(prefix + "-" + UUID.randomUUID() + "@example.com");
        return outcome.userId();
    }

    private UUID bootstrapCollegeAdmin(UUID tenantId) {
        UUID adminUserId = newUser("bootstrap-admin");
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), adminUserId, roleId, tenantId, Instant.now(), null));
        return adminUserId;
    }

    @Test
    void collegeAdminCanAssignFacultyRoleWithStepUp_andItIsAudited() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Test College", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID newFaculty = newUser("new-faculty");
        TestAuthentication.authenticateAs(admin, Instant.now());

        UUID assignmentId = roleAssignmentService.assignRole(newFaculty, RoleCode.FACULTY, tenant);

        assertThat(assignmentId).isNotNull();
        entityManager.flush();
        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.audit_logs WHERE action = 'ROLE_ASSIGNED' AND resource_id = ? AND actor_user_id = ?",
            Integer.class, assignmentId, admin);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void roleAssignmentWithoutStepUpIsDenied() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Test College 2", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID newFaculty = newUser("no-stepup-faculty");
        TestAuthentication.authenticateAs(admin, null);

        assertThatThrownBy(() -> roleAssignmentService.assignRole(newFaculty, RoleCode.FACULTY, tenant))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void facultyCannotAssignRoles_missingPermission() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Test College 3", "Asia/Kolkata");
        UUID faculty = newUser("plain-faculty");
        UUID roleId = roleRepository.findByCode(RoleCode.FACULTY).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), faculty, roleId, tenant, Instant.now(), null));
        TestAuthentication.authenticateAs(faculty, Instant.now());

        assertThatThrownBy(() -> roleAssignmentService.assignRole(newUser("victim"), RoleCode.STUDENT, tenant))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void studentRoleCanBeAssignedWithoutStepUp() {
        // The scoped-step-up product decision this class's own class-level javadoc doesn't yet describe:
        // STUDENT/GUARDIAN are routine onboarding, not privilege escalation, so they're deliberately exempt
        // from RoleAssignmentService's ELEVATED_ROLES step-up gate — see that field's own javadoc for why.
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Test College 5", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID newStudent = newUser("no-stepup-student");
        TestAuthentication.authenticateAs(admin, null); // no step-up completed

        UUID assignmentId = roleAssignmentService.assignRole(newStudent, RoleCode.STUDENT, tenant);

        assertThat(assignmentId).isNotNull();
    }

    @Test
    void guardianRoleCanBeAssignedWithoutStepUp() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Test College 6", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID newGuardian = newUser("no-stepup-guardian");
        TestAuthentication.authenticateAs(admin, null); // no step-up completed

        UUID assignmentId = roleAssignmentService.assignRole(newGuardian, RoleCode.GUARDIAN, tenant);

        assertThat(assignmentId).isNotNull();
    }

    @Test
    void placementOfficerRoleStillRequiresStepUp() {
        // PLACEMENT_OFFICER stays in ELEVATED_ROLES (a staff appointment, not routine onboarding) — this test
        // exists so a future edit to that set can't silently widen the exemption without a test noticing.
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Test College 7", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID newOfficer = newUser("no-stepup-placement");
        TestAuthentication.authenticateAs(admin, null); // no step-up completed

        assertThatThrownBy(() -> roleAssignmentService.assignRole(newOfficer, RoleCode.PLACEMENT_OFFICER, tenant))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aRoutineStudentRoleCanBeRevokedWithoutStepUpButAnElevatedRoleStillCannot() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Test College 8", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID student = newUser("to-be-unenrolled");
        UUID facultyUser = newUser("elevated-to-revoke");
        TestAuthentication.authenticateAs(admin, Instant.now());
        UUID studentAssignmentId = roleAssignmentService.assignRole(student, RoleCode.STUDENT, tenant);
        UUID facultyAssignmentId = roleAssignmentService.assignRole(facultyUser, RoleCode.FACULTY, tenant);

        TestAuthentication.authenticateAs(admin, null); // no step-up completed for either revoke below
        boolean studentRevoked = roleAssignmentService.revokeRole(studentAssignmentId);
        assertThat(studentRevoked).isTrue();

        assertThatThrownBy(() -> roleAssignmentService.revokeRole(facultyAssignmentId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void revokingARoleEndsItImmediately() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Test College 4", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID facultyUser = newUser("to-be-demoted");
        TestAuthentication.authenticateAs(admin, Instant.now());
        UUID assignmentId = roleAssignmentService.assignRole(facultyUser, RoleCode.FACULTY, tenant);

        boolean revoked = roleAssignmentService.revokeRole(assignmentId);

        assertThat(revoked).isTrue();
        UserRole reloaded = userRoleRepository.findById(assignmentId).orElseThrow();
        assertThat(reloaded.isActive(Instant.now())).isFalse();
    }

    @Test
    void assigningARolePublishesAUserRoleAssignedEventForOtherServices() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Event College", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID newFaculty = newUser("event-faculty");
        TestAuthentication.authenticateAs(admin, Instant.now());

        UUID assignmentId = roleAssignmentService.assignRole(newFaculty, RoleCode.FACULTY, tenant);

        entityManager.flush();
        var row = jdbc.queryForMap("SELECT tenant_id, aggregate_type, payload_json::text AS payload FROM governance.outbox_events "
            + "WHERE event_type = 'UserRoleAssigned' AND aggregate_id = ?", assignmentId);
        assertThat(row.get("tenant_id")).isEqualTo(tenant);
        assertThat(row.get("aggregate_type")).isEqualTo("user_roles");
        assertThat((String) row.get("payload")).contains(newFaculty.toString()).contains("FACULTY").contains(admin.toString());
    }

    @Test
    void revokingARolePublishesAUserRoleRevokedEvent() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "R" + UUID.randomUUID().toString().substring(0, 8), "Role Revoke Event College", "Asia/Kolkata");
        UUID admin = bootstrapCollegeAdmin(tenant);
        UUID student = newUser("event-student");
        TestAuthentication.authenticateAs(admin, null);
        UUID assignmentId = roleAssignmentService.assignRole(student, RoleCode.STUDENT, tenant);

        roleAssignmentService.revokeRole(assignmentId);

        entityManager.flush();
        var row = jdbc.queryForMap("SELECT tenant_id, payload_json::text AS payload FROM governance.outbox_events "
            + "WHERE event_type = 'UserRoleRevoked' AND aggregate_id = ?", assignmentId);
        assertThat(row.get("tenant_id")).isEqualTo(tenant);
        assertThat((String) row.get("payload")).contains(student.toString()).contains("STUDENT");
    }
}
