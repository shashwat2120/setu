package com.setu.identity.infrastructure.authorization;

import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.tenant.TenantContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The first real implementation of the {@link TenantContext} seam this
 * codebase has had since it was declared Day 1 — every prior module
 * resolved tenant from a specific target entity's own {@code tenantId}
 * instead (the documented, safe pattern: the resource's own tenantId
 * resolved server-side). Day 14 is the first caller that genuinely needs
 * "what tenant does the CURRENT principal themselves belong to" with no
 * target resource to resolve it from ({@code searchCollegePolicy} has no
 * per-student/per-section anchor) — so this fills the seam exactly as
 * originally specified, rather than inventing a parallel mechanism.
 * <p>
 * Resolution: the active (non-expired) tenant-scoped roles for the current
 * principal, collapsed to a single tenant id. Zero or more-than-one
 * distinct tenant is {@code Optional.empty()} — ambiguous, per this
 * interface's own contract ("before a cross-tenant user has selected
 * which tenant to act in") — never a guess, and never a value taken from
 * anything the caller (or an AI prompt) supplied.
 */
@Component
public class TenantContextImpl implements TenantContext {

    private final UserRoleRepository userRoleRepository;
    private final AuthorizationContext authorizationContext;

    public TenantContextImpl(UserRoleRepository userRoleRepository, AuthorizationContext authorizationContext) {
        this.userRoleRepository = userRoleRepository;
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
        Instant now = Instant.now();
        Set<UUID> tenantIds = userRoleRepository.findByUserId(userId).stream()
            .filter(r -> r.isActive(now) && r.tenantId() != null)
            .map(UserRole::tenantId)
            .collect(Collectors.toSet());
        return tenantIds.size() == 1 ? Optional.of(tenantIds.iterator().next()) : Optional.empty();
    }
}
