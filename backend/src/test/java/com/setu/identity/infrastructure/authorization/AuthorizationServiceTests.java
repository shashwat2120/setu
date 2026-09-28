package com.setu.identity.infrastructure.authorization;

import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.shared.authorization.AccessDecision;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The required Day 4 test matrix, items 1-6, 11-13, 17 — the engine-level
 * cases that don't need guardian delegation, step-up, or a real sensitive
 * operation on top (those get their own test classes). Runs against the
 * real Postgres and the real seeded role_permissions catalog (V55), not
 * fakes — a change to the seed data that broke a role's actual permissions
 * would fail these tests too.
 */
@SpringBootTest
@Transactional
class AuthorizationServiceTests {

    @Autowired
    AuthorizationContext authorizationContext;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private UUID newUser(String emailPrefix) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register(emailPrefix + "-" + UUID.randomUUID() + "@example.com");
        return outcome.userId();
    }

    private UUID newTenant() {
        return collegeDirectoryService.provisionCollege("C" + UUID.randomUUID().toString().substring(0, 8),
            "Test College", "Asia/Kolkata");
    }

    private void grantRole(UUID userId, RoleCode roleCode, UUID tenantId) {
        UUID roleId = roleRepository.findByCode(roleCode).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), userId, roleId, tenantId, Instant.now(), null));
    }

    // --- 1. role without permission ---
    @Test
    void roleWithoutPermissionIsDenied() {
        UUID tenant = newTenant();
        UUID user = newUser("no-perm");
        grantRole(user, RoleCode.STUDENT, tenant); // STUDENT never gets MANAGE_TENANT_USERS (V55)
        TestAuthentication.authenticateAs(user);

        var decision = authorizationContext.can("MANAGE_TENANT_USERS", ResourceRef.of("user", UUID.randomUUID(), tenant));

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
        assertThat(((AccessDecision.Deny) decision).reasonCode()).isEqualTo("permission_denied");
    }

    // --- 2. permission with wrong tenant ---
    @Test
    void permissionGrantedInADifferentTenantIsDenied() {
        UUID myTenant = newTenant();
        UUID otherTenant = newTenant();
        UUID admin = newUser("wrong-tenant-admin");
        grantRole(admin, RoleCode.COLLEGE_ADMIN, myTenant); // admin of myTenant only
        TestAuthentication.authenticateAs(admin);

        var decision = authorizationContext.can("MANAGE_TENANT_USERS",
            ResourceRef.of("user", UUID.randomUUID(), otherTenant)); // resource belongs to a DIFFERENT tenant

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
        assertThat(((AccessDecision.Deny) decision).reasonCode()).isEqualTo("tenant_mismatch");
    }

    // --- 3. correct tenant but wrong resource (no single owner; principal not in the assigned set) ---
    @Test
    void correctTenantButNotInAssignedSetIsDenied() {
        UUID tenant = newTenant();
        UUID faculty = newUser("unassigned-faculty");
        UUID someoneElse = newUser("actually-assigned-faculty");
        grantRole(faculty, RoleCode.FACULTY, tenant);
        TestAuthentication.authenticateAs(faculty);

        ResourceRef section = ResourceRef.of("course_section", UUID.randomUUID(), tenant)
            .assignedTo(Set.of(someoneElse)); // faculty is NOT in the assigned set

        var decision = authorizationContext.can("VIEW_ASSIGNED_SECTIONS", section);

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
        assertThat(((AccessDecision.Deny) decision).reasonCode()).isEqualTo("not_assigned");
    }

    // --- 4. wrong owner ---
    @Test
    void wrongOwnerIsDenied() {
        UUID tenant = newTenant();
        UUID student = newUser("wrong-owner-student");
        UUID actualOwner = newUser("actual-owner-student");
        grantRole(student, RoleCode.STUDENT, tenant);
        TestAuthentication.authenticateAs(student);

        ResourceRef record = ResourceRef.of("academic_record", UUID.randomUUID(), tenant).ownedBy(actualOwner);

        var decision = authorizationContext.can("VIEW_GRADES", record);

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
        assertThat(((AccessDecision.Deny) decision).reasonCode()).isEqualTo("not_owner_or_assigned");
    }

    // --- 5. wrong faculty assignment ---
    @Test
    void facultyNotAssignedToSectionCannotManageItsGrades() {
        UUID tenant = newTenant();
        UUID facultyA = newUser("faculty-a");
        UUID facultyB = newUser("faculty-b");
        grantRole(facultyA, RoleCode.FACULTY, tenant);
        TestAuthentication.authenticateAs(facultyA);

        ResourceRef sectionAssignedToB = ResourceRef.of("course_section", UUID.randomUUID(), tenant)
            .assignedTo(Set.of(facultyB));

        var decision = authorizationContext.can("MANAGE_SECTION_GRADES", sectionAssignedToB);

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
    }

    // --- 6. student accessing another student ---
    @Test
    void studentCannotAccessAnotherStudentsEnrollment() {
        UUID tenant = newTenant();
        UUID studentA = newUser("student-a");
        UUID studentB = newUser("student-b");
        grantRole(studentA, RoleCode.STUDENT, tenant);
        TestAuthentication.authenticateAs(studentA);

        ResourceRef studentBsEnrollment = ResourceRef.of("enrollment", UUID.randomUUID(), tenant).ownedBy(studentB);

        var decision = authorizationContext.can("VIEW_ENROLLMENTS", studentBsEnrollment);

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
    }

    // --- 11. cross-tenant UUID access (a syntactically valid resource id, just in a tenant the principal has no role in) ---
    @Test
    void crossTenantResourceIdIsDenied_notRelyingOnUuidSecrecy() {
        UUID myTenant = newTenant();
        UUID victimTenant = newTenant();
        UUID student = newUser("cross-tenant-student");
        grantRole(student, RoleCode.STUDENT, myTenant);
        TestAuthentication.authenticateAs(student);

        // A perfectly valid, real resource id — just owned by a different tenant.
        ResourceRef victimResource = ResourceRef.of("academic_record", UUID.randomUUID(), victimTenant).ownedBy(student);

        var decision = authorizationContext.can("VIEW_GRADES", victimResource);

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
        assertThat(((AccessDecision.Deny) decision).reasonCode()).isEqualTo("tenant_mismatch");
    }

    // --- 12. privilege escalation ---
    @Test
    void studentCannotEscalateToPlatformRoleManagement() {
        UUID tenant = newTenant();
        UUID student = newUser("escalating-student");
        grantRole(student, RoleCode.STUDENT, tenant);
        TestAuthentication.authenticateAs(student);

        var decision = authorizationContext.can("MANAGE_ROLES_PLATFORM", ResourceRef.of("user_role", UUID.randomUUID(), null));

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
        assertThat(((AccessDecision.Deny) decision).reasonCode()).isEqualTo("permission_denied");
    }

    // --- 13. invalid workflow state ---
    @Test
    void workflowBlockedResourceIsDeniedRegardlessOfPermission() {
        UUID tenant = newTenant();
        UUID facultyUser = newUser("workflow-faculty");
        grantRole(facultyUser, RoleCode.FACULTY, tenant);
        TestAuthentication.authenticateAs(facultyUser);

        ResourceRef lockedSection = ResourceRef.of("course_section", UUID.randomUUID(), tenant)
            .assignedTo(Set.of(facultyUser))
            .withWorkflowBlocked(true); // e.g. section locked/published — domain logic already decided this

        var decision = authorizationContext.can("MANAGE_SECTION_GRADES", lockedSection);

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
        assertThat(((AccessDecision.Deny) decision).reasonCode()).isEqualTo("invalid_workflow_state");
    }

    // --- 17. successful authorized operation ---
    @Test
    void correctlyScopedRequestIsAllowed() {
        UUID tenant = newTenant();
        UUID student = newUser("happy-path-student");
        grantRole(student, RoleCode.STUDENT, tenant);
        TestAuthentication.authenticateAs(student);

        ResourceRef ownRecord = ResourceRef.of("academic_record", UUID.randomUUID(), tenant).ownedBy(student);

        var decision = authorizationContext.can("VIEW_GRADES", ownRecord);

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void superAdminBypassesTenantAndOwnershipButNotPermissionOrWorkflow() {
        UUID tenant = newTenant();
        UUID otherStudent = newUser("some-student");
        UUID admin = newUser("super-admin-user");
        grantRole(admin, RoleCode.SUPER_ADMIN, null); // platform-scoped
        TestAuthentication.authenticateAs(admin);

        ResourceRef someonesRecord = ResourceRef.of("academic_record", UUID.randomUUID(), tenant).ownedBy(otherStudent);
        assertThat(authorizationContext.can("VIEW_GRADES", someonesRecord).isAllowed()).isTrue();

        ResourceRef blockedRecord = someonesRecord.withWorkflowBlocked(true);
        assertThat(authorizationContext.can("VIEW_GRADES", blockedRecord)).isInstanceOf(AccessDecision.Deny.class);

        var noSuchPermission = authorizationContext.can("NOT_A_REAL_PERMISSION_CODE", someonesRecord);
        assertThat(noSuchPermission).isInstanceOf(AccessDecision.Deny.class);
    }

    @Test
    void unauthenticatedRequestIsDenied() {
        TestAuthentication.clear();
        var decision = authorizationContext.can("VIEW_PROFILE", ResourceRef.of("person", UUID.randomUUID(), null));
        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
        assertThat(((AccessDecision.Deny) decision).reasonCode()).isEqualTo("unauthenticated");
    }
}
