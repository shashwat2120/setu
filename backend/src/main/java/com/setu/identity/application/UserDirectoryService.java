package com.setu.identity.application;

import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserPrincipal;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.shared.identity.TenantMembership;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A small identity read-port other modules use to validate a user reference
 * they don't own — e.g. institution.DepartmentService verifying a proposed
 * HOD actually holds an active role in the department's own tenant before
 * accepting the assignment (Day 5 instruction §6: "do not automatically
 * create HOD relationships without verifying that the referenced identity
 * belongs to the same authorized college").
 * <p>
 * {@code @NamedInterface} on this class only (Day 5 §13) — not the whole
 * {@code application} package.
 */
@org.springframework.modulith.NamedInterface
@Service
public class UserDirectoryService implements TenantMembership {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;

    public UserDirectoryService(UserRoleRepository userRoleRepository, RoleRepository roleRepository) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
    }

    public boolean hasActiveRoleInTenant(UUID userId, UUID tenantId) {
        Instant now = Instant.now();
        return userRoleRepository.findByUserId(userId).stream()
            .anyMatch(ur -> tenantId.equals(ur.tenantId()) && ur.isActive(now));
    }

    /**
     * Same check, excluding GUARDIAN — for callers where "any tenant member may look this up, like a real
     * roster lookup" is the right rule for every role EXCEPT guardian, whose access must only ever come through
     * an explicit, scoped {@code guardianaccess.GuardianAccessGrant} (Day 6 §8), never blanket tenant membership.
     * {@link com.setu.directory.application.StudentProfileService#get} is the real caller this exists for: a
     * guardian holding the GUARDIAN role in a tenant must not be able to browse every OTHER student's basic
     * profile in that tenant just because they have a role there — only faculty/admin/placement-officer-style
     * "any role" access is intended by the original relaxation.
     */
    @Override
    public boolean hasActiveNonGuardianRoleInTenant(UUID userId, UUID tenantId) {
        Instant now = Instant.now();
        return userRoleRepository.findByUserId(userId).stream()
            .filter(ur -> tenantId.equals(ur.tenantId()) && ur.isActive(now))
            .anyMatch(ur -> roleRepository.findById(ur.roleId())
                .map(role -> role.code() != RoleCode.GUARDIAN)
                .orElse(false));
    }

    /**
     * Day 15 §demo-data: grants a role with NO authorization check at all —
     * deliberately, and only defensible because of what it is: bootstrap
     * role assignment for a brand-new account that cannot yet hold any
     * permission to check against. {@link RoleAssignmentService#assignRole}
     * is the real, ongoing, HTTP-reachable path for granting a role to an
     * account that already exists inside a running, authenticated system —
     * it requires step-up reauthentication and {@code MANAGE_TENANT_ROLES}/
     * {@code MANAGE_ROLES_PLATFORM}, which is exactly right for that case
     * and exactly impossible to satisfy for the FIRST account a fresh
     * tenant (or the platform) ever gets. Every test fixture in this
     * codebase already grants roles this same way, directly, for the same
     * reason (see e.g. {@code Day7TestFixtures#provisionUserWithRole}).
     * <p>
     * Never called from any {@code @RestController} — only {@code
     * shared.seed.DemoDataSeeder}, itself gated behind {@code
     * app.demo-data.enabled} (default {@code false}) and never reachable
     * over HTTP.
     */
    @Transactional
    public UUID grantBootstrapRole(UUID userId, RoleCode roleCode, UUID tenantId) {
        UUID roleId = roleRepository.findByCode(roleCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown role code: " + roleCode)).id();
        UserRole assignment = new UserRole(UUID.randomUUID(), userId, roleId, tenantId, Instant.now(), null);
        userRoleRepository.save(assignment);
        return assignment.id();
    }

    /**
     * Day 15 §demo-data: populates the current thread's {@code SecurityContext} with a real {@link UserPrincipal}
     * for {@code userId}, exactly the way {@code JwtAuthenticationFilter} would for a genuine HTTP request.
     * {@code shared.seed.DemoDataSeeder} has no HTTP request of its own, so every authorization-gated service it
     * calls (colleges, enrollments, grades, payments, ...) needs a real, resolvable principal to check against —
     * this method exists here, inside identity, specifically so that principal-construction logic never has to
     * live in (or be exposed to) another module: {@link UserPrincipal} is identity's own domain type, and every
     * other module in this codebase reaches identity only through {@code identity.application}, never through
     * {@code identity.domain} directly. {@code stepUpAt} is always set to the current instant, so a seed step
     * that happens to require step-up reauthentication is never spuriously blocked.
     */
    public void authenticateForSeed(UUID userId) {
        UserPrincipal principal = new UserPrincipal(userId, "seed-jti-" + UUID.randomUUID(), Instant.now());
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Day 15 §demo-data: called once, after {@link com.setu.shared.seed.DemoDataSeeder} finishes (or fails), so no seed-authenticated context ever leaks into a later request. */
    public void clearSeedAuthentication() {
        SecurityContextHolder.clearContext();
    }
}
