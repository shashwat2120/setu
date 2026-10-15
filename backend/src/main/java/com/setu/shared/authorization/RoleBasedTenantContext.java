package com.setu.shared.authorization;

import com.setu.shared.tenant.TenantContext;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link TenantContext} from the caller's active tenant-scoped roles, collapsed to one tenant. Zero or more than one distinct tenant is
 * {@code Optional.empty()}: ambiguous, never a guess, and never a value taken from anything the caller (or an AI prompt) supplied.
 */
public class RoleBasedTenantContext implements TenantContext {

    private final RoleGrantSource roles;
    private final AuthorizationContext authorizationContext;

    public RoleBasedTenantContext(RoleGrantSource roles, AuthorizationContext authorizationContext) {
        this.roles = roles;
        this.authorizationContext = authorizationContext;
    }

    @Override
    public Optional<UUID> tenantId() {
        UUID userId;
        try {
            userId = authorizationContext.currentUserId();
        } catch (AccessDeniedException e) {
            return Optional.empty();
        }
        Set<UUID> tenantIds = roles.activeGrants(userId, Instant.now()).stream()
            .filter(g -> g.tenantId() != null)
            .map(RoleGrant::tenantId)
            .collect(Collectors.toSet());
        return tenantIds.size() == 1 ? Optional.of(tenantIds.iterator().next()) : Optional.empty();
    }
}
