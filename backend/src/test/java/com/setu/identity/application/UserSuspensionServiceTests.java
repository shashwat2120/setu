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
 * Test-matrix items 15, 16, 18 — a real sensitive operation (emergency
 * suspension) exercised end to end: unauthorized caller, missing step-up,
 * and the resulting audit trail.
 */
@SpringBootTest
@Transactional
class UserSuspensionServiceTests {

    @Autowired
    UserSuspensionService suspensionService;
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

    private UUID newTenant() {
        return collegeDirectoryService.provisionCollege("S" + UUID.randomUUID().toString().substring(0, 8),
            "Suspension Test College", "Asia/Kolkata");
    }

    private void grantRole(UUID userId, RoleCode roleCode, UUID tenantId, Instant now) {
        UUID roleId = roleRepository.findByCode(roleCode).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), userId, roleId, tenantId, now, null));
    }

    // --- 15. unauthorized admin operation ---
    @Test
    void suspensionByUserWithoutSuspendPermissionIsDenied() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target");
        UUID unauthorizedActor = newUser("mere-student");
        grantRole(unauthorizedActor, RoleCode.STUDENT, tenant, Instant.now()); // STUDENT never has SUSPEND_USER
        TestAuthentication.authenticateAs(unauthorizedActor, Instant.now()); // even WITH step-up, permission is still missing

        assertThatThrownBy(() -> suspensionService.suspend(target, tenant, "test"))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- 16. step-up required operation without step-up ---
    @Test
    void suspensionWithoutStepUpIsDenied_evenWithCorrectPermission() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target-2");
        UUID admin = newUser("college-admin-no-stepup");
        grantRole(admin, RoleCode.COLLEGE_ADMIN, tenant, Instant.now());
        TestAuthentication.authenticateAs(admin, null); // no step-up claim at all

        assertThatThrownBy(() -> suspensionService.suspend(target, tenant, "test"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staleStepUpIsTreatedAsNoStepUp() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target-3");
        UUID admin = newUser("college-admin-stale-stepup");
        grantRole(admin, RoleCode.COLLEGE_ADMIN, tenant, Instant.now());
        // Default step-up validity is 5 minutes (app.security.authorization.step-up-validity-minutes) — 1 hour old is well past that.
        TestAuthentication.authenticateAs(admin, Instant.now().minus(java.time.Duration.ofHours(1)));

        assertThatThrownBy(() -> suspensionService.suspend(target, tenant, "test"))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- 17/18 combined: successful authorized + audited sensitive operation ---
    @Test
    void authorizedSuspensionWithStepUpSucceedsAndIsAudited() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target-4");
        UUID admin = newUser("college-admin-with-stepup");
        grantRole(admin, RoleCode.COLLEGE_ADMIN, tenant, Instant.now());
        TestAuthentication.authenticateAs(admin, Instant.now());

        var result = suspensionService.suspend(target, tenant, "policy violation");

        assertThat(result).isEqualTo(UserSuspensionService.Result.SUSPENDED);
        entityManager.flush(); // AuditLogWriter's JPA save() is otherwise still pending when the raw JdbcTemplate query below runs
        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.audit_logs WHERE action = 'USER_SUSPENDED' AND resource_id = ? AND actor_user_id = ?",
            Integer.class, target, admin);
        assertThat(auditCount).isEqualTo(1);
    }
}
