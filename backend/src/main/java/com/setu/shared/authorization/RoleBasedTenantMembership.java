package com.setu.shared.authorization;

import com.setu.shared.identity.TenantMembership;

import java.time.Instant;
import java.util.UUID;

/** {@link TenantMembership} answered from the role grants a {@link RoleGrantSource} holds, for services that do not own the role tables. */
public class RoleBasedTenantMembership implements TenantMembership {

    private final RoleGrantSource roles;

    public RoleBasedTenantMembership(RoleGrantSource roles) {
        this.roles = roles;
    }

    @Override
    public boolean hasActiveRoleInTenant(UUID userId, UUID tenantId) {
        return roles.activeGrants(userId, Instant.now()).stream().anyMatch(g -> tenantId.equals(g.tenantId()));
    }

    @Override
    public boolean hasActiveNonGuardianRoleInTenant(UUID userId, UUID tenantId) {
        return roles.activeGrants(userId, Instant.now()).stream()
            .anyMatch(g -> tenantId.equals(g.tenantId()) && !RoleGrant.GUARDIAN.equals(g.roleCode()));
    }
}
