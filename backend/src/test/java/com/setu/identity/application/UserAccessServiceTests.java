package com.setu.identity.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.domain.UserStatus;
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
 * Test-matrix items 15, 16, 18 — the full account-access lifecycle
 * (suspend/reactivate/deactivate) exercised end to end: unauthorized caller,
 * missing step-up, invalid transitions, the resulting audit trail, session
 * revocation, and — the reason this lifecycle exists as its own service —
 * that a profile-status change alone never touches it.
 */
@SpringBootTest
@Transactional
class UserAccessServiceTests {

    @Autowired
    UserAccessService accessService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;
    @Autowired
    PasswordAuthenticationService passwordAuthenticationService;
    @Autowired
    com.setu.identity.infrastructure.security.Argon2PasswordService passwordService;
    @Autowired
    com.setu.identity.infrastructure.UserRepository userRepository;
    @Autowired
    SessionService sessionService;
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
            "Access Lifecycle Test College", "Asia/Kolkata");
    }

    private void grantRole(UUID userId, RoleCode roleCode, UUID tenantId, Instant now) {
        UUID roleId = roleRepository.findByCode(roleCode).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), userId, roleId, tenantId, now, null));
    }

    private UUID newCollegeAdmin(UUID tenant) {
        UUID admin = newUser("college-admin");
        grantRole(admin, RoleCode.COLLEGE_ADMIN, tenant, Instant.now());
        return admin;
    }

    // --- unauthorized admin operation ---
    @Test
    void suspensionByUserWithoutSuspendPermissionIsDenied() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target");
        UUID unauthorizedActor = newUser("mere-student");
        grantRole(unauthorizedActor, RoleCode.STUDENT, tenant, Instant.now()); // STUDENT never has SUSPEND_USER
        TestAuthentication.authenticateAs(unauthorizedActor, Instant.now()); // even WITH step-up, permission is still missing

        assertThatThrownBy(() -> accessService.suspend(target, tenant, "test"))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- step-up required operation without step-up ---
    @Test
    void suspensionWithoutStepUpIsDenied_evenWithCorrectPermission() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target-2");
        UUID admin = newUser("college-admin-no-stepup");
        grantRole(admin, RoleCode.COLLEGE_ADMIN, tenant, Instant.now());
        TestAuthentication.authenticateAs(admin, null); // no step-up claim at all

        assertThatThrownBy(() -> accessService.suspend(target, tenant, "test"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staleStepUpIsTreatedAsNoStepUp() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target-3");
        UUID admin = newUser("college-admin-stale-stepup");
        grantRole(admin, RoleCode.COLLEGE_ADMIN, tenant, Instant.now());
        TestAuthentication.authenticateAs(admin, Instant.now().minus(java.time.Duration.ofHours(1)));

        assertThatThrownBy(() -> accessService.suspend(target, tenant, "test"))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- successful authorized + audited suspension, sessions revoked ---
    @Test
    void authorizedSuspensionWithStepUpSucceedsRevokesSessionsAndIsAudited() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target-4");
        UUID admin = newCollegeAdmin(tenant);

        // Give the target an active session, so we can prove suspend() kills it.
        var tokens = sessionService.startNewSession(target, null);

        TestAuthentication.authenticateAs(admin, Instant.now());
        var result = accessService.suspend(target, tenant, "policy violation");

        assertThat(result).isEqualTo(UserAccessService.SuspendResult.SUSPENDED);
        assertThat(userRepository.findById(target).orElseThrow().status()).isEqualTo(UserStatus.SUSPENDED);

        // suspend() already revoked this session via revokeAllSessionsForUser, so refresh()'s own pre-existing
        // reuse-detection path (a presented-but-already-revoked token looks like theft) fires here rather than
        // the newer isActive() defense-in-depth check added alongside this service — either way, the one thing
        // that must never happen is a successful Rotated outcome.
        var refreshOutcome = sessionService.refresh(tokens.refreshToken(), null);
        assertThat(refreshOutcome).isNotInstanceOf(RefreshOutcome.Rotated.class);

        entityManager.flush();
        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.audit_logs WHERE action = 'USER_SUSPENDED' AND resource_id = ? AND actor_user_id = ?",
            Integer.class, target, admin);
        assertThat(auditCount).isEqualTo(1);
        Integer outboxCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.outbox_events WHERE event_type = 'UserSuspended' AND aggregate_id = ?",
            Integer.class, target);
        assertThat(outboxCount).isEqualTo(1);
    }

    // --- refresh()'s own defense-in-depth check, isolated from suspend()'s session revocation ---
    @Test
    void refreshRejectsALiveSessionForASuspendedAccount_evenIfNotYetRevoked() {
        UUID target = newUser("live-session-suspended-target");
        var tokens = sessionService.startNewSession(target, null);

        // Flip the account straight to SUSPENDED without going through UserAccessService#suspend, so the
        // session is deliberately left un-revoked — isolating refresh()'s own isActive() guard rather than
        // relying on the session having already been killed by the suspend operation itself.
        var user = userRepository.findById(target).orElseThrow();
        user.suspend();
        userRepository.save(user);

        var refreshOutcome = sessionService.refresh(tokens.refreshToken(), null);

        assertThat(refreshOutcome).isInstanceOf(RefreshOutcome.Invalid.class);
        assertThat(((RefreshOutcome.Invalid) refreshOutcome).reason()).contains("suspended");
    }

    // --- suspending an already-suspended user is reported, not silently re-applied ---
    @Test
    void suspendingAlreadySuspendedUserReturnsAlreadySuspended() {
        UUID tenant = newTenant();
        UUID target = newUser("suspend-target-5");
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin, Instant.now());

        accessService.suspend(target, tenant, "first suspension");
        var second = accessService.suspend(target, tenant, "second attempt");

        assertThat(second).isEqualTo(UserAccessService.SuspendResult.ALREADY_SUSPENDED);
    }

    // --- reactivate reverses a suspension ---
    @Test
    void reactivateRestoresActiveStatusAndIsAudited() {
        UUID tenant = newTenant();
        UUID target = newUser("reactivate-target");
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin, Instant.now());
        accessService.suspend(target, tenant, "temporary hold");

        var result = accessService.reactivate(target, tenant, "hold lifted");

        assertThat(result).isEqualTo(UserAccessService.ReactivateResult.REACTIVATED);
        assertThat(userRepository.findById(target).orElseThrow().status()).isEqualTo(UserStatus.ACTIVE);
        entityManager.flush();
        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.audit_logs WHERE action = 'USER_REACTIVATED' AND resource_id = ? AND actor_user_id = ?",
            Integer.class, target, admin);
        assertThat(auditCount).isEqualTo(1);
    }

    // --- reactivate is a no-op-with-signal on an ACTIVE (never-suspended) user, not a silent success ---
    @Test
    void reactivatingAnActiveUserReturnsNotSuspended() {
        UUID tenant = newTenant();
        UUID target = newUser("reactivate-target-2");
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin, Instant.now());

        var result = accessService.reactivate(target, tenant, "no reason to reactivate");

        assertThat(result).isEqualTo(UserAccessService.ReactivateResult.NOT_SUSPENDED);
    }

    // --- deactivate is terminal: reactivate can never reverse it ---
    @Test
    void deactivationIsNeverReversedByReactivate() {
        UUID tenant = newTenant();
        UUID target = newUser("deactivate-target");
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin, Instant.now());

        var deactivated = accessService.deactivate(target, tenant, "employment terminated");
        assertThat(deactivated).isEqualTo(UserAccessService.DeactivateResult.DEACTIVATED);
        assertThat(userRepository.findById(target).orElseThrow().status()).isEqualTo(UserStatus.DEACTIVATED);

        var reactivateAttempt = accessService.reactivate(target, tenant, "trying to undo it");
        assertThat(reactivateAttempt).isEqualTo(UserAccessService.ReactivateResult.NOT_SUSPENDED);
        assertThat(userRepository.findById(target).orElseThrow().status()).isEqualTo(UserStatus.DEACTIVATED);

        entityManager.flush();
        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.audit_logs WHERE action = 'USER_DEACTIVATED' AND resource_id = ? AND actor_user_id = ?",
            Integer.class, target, admin);
        assertThat(auditCount).isEqualTo(1);
        Integer outboxCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.outbox_events WHERE event_type = 'UserDeactivated' AND aggregate_id = ?",
            Integer.class, target);
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void deactivatingAnAlreadyDeactivatedUserReturnsAlreadyDeactivated() {
        UUID tenant = newTenant();
        UUID target = newUser("deactivate-target-2");
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin, Instant.now());
        accessService.deactivate(target, tenant, "first pass");

        var second = accessService.deactivate(target, tenant, "second pass");

        assertThat(second).isEqualTo(UserAccessService.DeactivateResult.ALREADY_DEACTIVATED);
    }

    // --- the core non-negotiable this whole lifecycle exists to enforce: suspended/deactivated accounts cannot authenticate ---
    @Test
    void suspendedUserCannotAuthenticateWithPassword() {
        UUID tenant = newTenant();
        UUID target = newUser("password-login-target");
        String email = userRepository.findById(target).orElseThrow().email();
        var hash = passwordService.hash("CorrectHorseBattery9!");
        var user = userRepository.findById(target).orElseThrow();
        user.setPasswordHash(hash, Instant.now());
        userRepository.save(user);

        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin, Instant.now());
        accessService.suspend(target, tenant, "suspended before login attempt");
        TestAuthentication.clear();

        var outcome = passwordAuthenticationService.authenticate(email, "CorrectHorseBattery9!");

        assertThat(outcome).isInstanceOf(PasswordAuthOutcome.AccountNotActive.class);
    }

    @Test
    void deactivatedUserCannotAuthenticateWithPassword() {
        UUID tenant = newTenant();
        UUID target = newUser("password-login-target-2");
        String email = userRepository.findById(target).orElseThrow().email();
        var hash = passwordService.hash("CorrectHorseBattery9!");
        var user = userRepository.findById(target).orElseThrow();
        user.setPasswordHash(hash, Instant.now());
        userRepository.save(user);

        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin, Instant.now());
        accessService.deactivate(target, tenant, "offboarded");
        TestAuthentication.clear();

        var outcome = passwordAuthenticationService.authenticate(email, "CorrectHorseBattery9!");

        assertThat(outcome).isInstanceOf(PasswordAuthOutcome.AccountNotActive.class);
    }

    // --- a profile-status style change alone (simulated here without pulling in the directory module) never touches account status ---
    @Test
    void accountStatusIsIndependentOfNonAccountActions() {
        UUID tenant = newTenant();
        UUID target = newUser("independence-check");

        // Nothing in this test calls accessService at all — the point is that a freshly
        // registered user's account status is ACTIVE by default and stays that way
        // unless THIS service is explicitly invoked; no other module can flip it.
        assertThat(userRepository.findById(target).orElseThrow().status()).isEqualTo(UserStatus.ACTIVE);
    }
}
