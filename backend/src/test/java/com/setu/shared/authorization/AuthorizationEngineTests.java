package com.setu.shared.authorization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decision rules every service applies, tested against an in-memory {@link RoleGrantSource} so they hold regardless of where the roles
 * come from (Identity's tables or an extracted service's read model). One case per rule in the engine's documented order.
 */
class AuthorizationEngineTests {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private final Map<UUID, List<RoleGrant>> grants = new HashMap<>();
    private final Map<String, Set<String>> permissions = Map.of(
        "COLLEGE_ADMIN", Set.of("MANAGE_COURSES", "VIEW_TENANT_DATA"),
        "FACULTY", Set.of("VIEW_TENANT_DATA", "MARK_ATTENDANCE"),
        "SUPER_ADMIN", Set.of("MANAGE_COURSES", "VIEW_TENANT_DATA"),
        "GUARDIAN", Set.of());
    private GuardianDelegationChecker delegation;
    private AuthorizationEngine engine;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RoleGrantSource source = new RoleGrantSource() {
            @Override
            public List<RoleGrant> activeGrants(UUID userId, Instant now) {
                return grants.getOrDefault(userId, List.of());
            }

            @Override
            public Set<String> permissionsOf(Collection<String> roleCodes) {
                return roleCodes.stream().flatMap(c -> permissions.getOrDefault(c, Set.of()).stream()).collect(Collectors.toSet());
            }
        };
        ObjectProvider<GuardianDelegationChecker> provider = new ObjectProvider<>() {
            @Override
            public GuardianDelegationChecker getObject() {
                return delegation;
            }

            @Override
            public GuardianDelegationChecker getObject(Object... args) {
                return delegation;
            }

            @Override
            public GuardianDelegationChecker getIfAvailable() {
                return delegation;
            }

            @Override
            public GuardianDelegationChecker getIfUnique() {
                return delegation;
            }
        };
        engine = new AuthorizationEngine(source, provider, 5);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(UUID userId, Instant stepUpAt) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(new ServicePrincipal(userId, "j", stepUpAt, null), null, List.of()));
    }

    private void grant(UUID userId, String role, UUID tenant) {
        grants.computeIfAbsent(userId, k -> new java.util.ArrayList<>()).add(new RoleGrant(role, tenant));
    }

    @Test
    void anUnauthenticatedCallerIsDenied() {
        assertThat(engine.can("VIEW_TENANT_DATA", ResourceRef.of("course", UUID.randomUUID(), TENANT_A)).isAllowed()).isFalse();
        assertThatThrownBy(engine::currentUserId).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aWorkflowBlockedResourceIsDeniedEvenForSuperAdmin() {
        grant(user, "SUPER_ADMIN", null);
        signIn(user, null);
        ResourceRef blocked = ResourceRef.of("section", UUID.randomUUID(), TENANT_A).withWorkflowBlocked(true);
        assertThat(engine.can("MANAGE_COURSES", blocked)).isEqualTo(new AccessDecision.Deny("invalid_workflow_state"));
    }

    @Test
    void aRoleInAnotherTenantDoesNotCount() {
        grant(user, "COLLEGE_ADMIN", TENANT_B);
        signIn(user, null);
        assertThat(engine.can("MANAGE_COURSES", ResourceRef.of("course", UUID.randomUUID(), TENANT_A)))
            .isEqualTo(new AccessDecision.Deny("tenant_mismatch"));
    }

    @Test
    void aTenantRoleWithThePermissionIsAllowed() {
        grant(user, "COLLEGE_ADMIN", TENANT_A);
        signIn(user, null);
        assertThat(engine.can("MANAGE_COURSES", ResourceRef.of("course", UUID.randomUUID(), TENANT_A)).isAllowed()).isTrue();
    }

    @Test
    void aRoleWithoutThePermissionIsDenied() {
        grant(user, "FACULTY", TENANT_A);
        signIn(user, null);
        assertThat(engine.can("MANAGE_COURSES", ResourceRef.of("course", UUID.randomUUID(), TENANT_A)))
            .isEqualTo(new AccessDecision.Deny("permission_denied"));
    }

    @Test
    void superAdminCrossesTenantsAndSkipsOwnership() {
        grant(user, "SUPER_ADMIN", null);
        signIn(user, null);
        ResourceRef owned = ResourceRef.of("course", UUID.randomUUID(), TENANT_A).ownedBy(UUID.randomUUID());
        assertThat(engine.can("MANAGE_COURSES", owned).isAllowed()).isTrue();
    }

    @Test
    void ownershipIsEnforcedForOrdinaryRoles() {
        grant(user, "FACULTY", TENANT_A);
        signIn(user, null);
        ResourceRef someoneElses = ResourceRef.of("section", UUID.randomUUID(), TENANT_A).ownedBy(UUID.randomUUID());
        assertThat(engine.can("VIEW_TENANT_DATA", someoneElses)).isEqualTo(new AccessDecision.Deny("not_owner_or_assigned"));
        assertThat(engine.can("VIEW_TENANT_DATA", ResourceRef.of("section", UUID.randomUUID(), TENANT_A).ownedBy(user)).isAllowed()).isTrue();
    }

    @Test
    void anAssignedSetWithoutAnOwnerAdmitsOnlyThoseAssigned() {
        grant(user, "FACULTY", TENANT_A);
        signIn(user, null);
        ResourceRef notAssigned = ResourceRef.of("section", UUID.randomUUID(), TENANT_A).assignedTo(Set.of(UUID.randomUUID()));
        assertThat(engine.can("VIEW_TENANT_DATA", notAssigned)).isEqualTo(new AccessDecision.Deny("not_assigned"));
        assertThat(engine.can("VIEW_TENANT_DATA", ResourceRef.of("section", UUID.randomUUID(), TENANT_A).assignedTo(Set.of(user))).isAllowed()).isTrue();
    }

    @Test
    void aGuardianWithAnActiveDelegationIsAllowedAndWithoutOneIsNot() {
        UUID student = UUID.randomUUID();
        grant(user, "GUARDIAN", TENANT_A);
        signIn(user, null);
        ResourceRef studentData = ResourceRef.of("grade", UUID.randomUUID(), TENANT_A).delegatableForStudent(student);

        assertThat(engine.can("VIEW_GRADES", studentData)).isEqualTo(new AccessDecision.Deny("permission_denied"));   // no checker in this process

        delegation = (guardian, studentId, tenant, permission, now) -> studentId.equals(student) && "VIEW_GRADES".equals(permission);
        assertThat(engine.can("VIEW_GRADES", studentData).isAllowed()).isTrue();
        assertThat(engine.can("VIEW_FEES", studentData)).isEqualTo(new AccessDecision.Deny("permission_denied"));
    }

    @Test
    void stepUpMustBeRecent() {
        signIn(user, Instant.now().minusSeconds(60));
        assertThat(engine.hasRecentStepUp()).isTrue();
        signIn(user, Instant.now().minusSeconds(60 * 30));
        assertThat(engine.hasRecentStepUp()).isFalse();
        assertThatThrownBy(engine::assertStepUp).isInstanceOf(AccessDeniedException.class);
        signIn(user, null);
        assertThat(engine.hasRecentStepUp()).isFalse();
    }

    @Test
    void canAsUserDecidesForTheGivenUserNotTheCurrentOne() {
        UUID other = UUID.randomUUID();
        grant(other, "COLLEGE_ADMIN", TENANT_A);
        signIn(user, null);   // the current caller has nothing
        assertThat(engine.canAsUser(other, "MANAGE_COURSES", ResourceRef.of("course", UUID.randomUUID(), TENANT_A)).isAllowed()).isTrue();
        assertThat(engine.can("MANAGE_COURSES", ResourceRef.of("course", UUID.randomUUID(), TENANT_A)).isAllowed()).isFalse();
    }

    @Test
    void tenantContextCollapsesToOneTenantOrNothing() {
        RoleGrantSource source = new RoleGrantSource() {
            @Override
            public List<RoleGrant> activeGrants(UUID userId, Instant now) {
                return grants.getOrDefault(userId, List.of());
            }

            @Override
            public Set<String> permissionsOf(Collection<String> roleCodes) {
                return Set.of();
            }
        };
        RoleBasedTenantContext context = new RoleBasedTenantContext(source, engine);
        assertThat(context.tenantId()).isEmpty();                       // not signed in
        signIn(user, null);
        assertThat(context.tenantId()).isEmpty();                       // no roles
        grant(user, "FACULTY", TENANT_A);
        assertThat(context.tenantId()).contains(TENANT_A);
        grant(user, "FACULTY", TENANT_B);
        assertThat(context.tenantId()).isEmpty();                       // ambiguous: never a guess
        RoleBasedTenantMembership membership = new RoleBasedTenantMembership(source);
        assertThat(membership.hasActiveRoleInTenant(user, TENANT_A)).isTrue();
        assertThat(membership.hasActiveRoleInTenant(user, UUID.randomUUID())).isFalse();
    }

    @Test
    void aGuardianOnlyMembershipIsNotANonGuardianMembership() {
        grant(user, "GUARDIAN", TENANT_A);
        RoleBasedTenantMembership membership = new RoleBasedTenantMembership(new RoleGrantSource() {
            @Override
            public List<RoleGrant> activeGrants(UUID userId, Instant now) {
                return grants.getOrDefault(userId, List.of());
            }

            @Override
            public Set<String> permissionsOf(Collection<String> roleCodes) {
                return Set.of();
            }
        });
        assertThat(membership.hasActiveRoleInTenant(user, TENANT_A)).isTrue();
        assertThat(membership.hasActiveNonGuardianRoleInTenant(user, TENANT_A)).isFalse();
    }
}
