package com.setu.identity.infrastructure.authorization;

import com.setu.identity.domain.Role;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserPrincipal;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RolePermissionRepository;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.shared.authorization.AccessDecision;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.GuardianDelegationChecker;
import com.setu.shared.authorization.ResourceRef;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The real {@code can(principal, permission, resource, action)} engine
 * (Day 4 instruction §10). Decision algorithm, in order — the first
 * matching rule decides:
 * <ol>
 *   <li>Unauthenticated -&gt; deny.</li>
 *   <li>Resource flagged {@code workflowBlocked} by its own domain logic -&gt;
 *       deny unconditionally, before any role/ownership check runs
 *       (Day 4 instruction §7 — permission alone is insufficient).</li>
 *   <li>Tenant check: a tenant-scoped resource requires an ACTIVE
 *       tenant-scoped role in that EXACT tenant, unless the principal holds
 *       an active platform-scoped SUPER_ADMIN role. The tenant used here is
 *       always the resource's own {@code tenantId} — resolved server-side
 *       by whichever module built the {@link ResourceRef} from ITS OWN
 *       entity, never a client-supplied value (Day 4 instruction §3).</li>
 *   <li>Permission check: granted either by one of the principal's
 *       tenant-matching roles (identity.role_permissions) OR, for a resource
 *       naming a delegation target student, by an active guardian
 *       delegation (Day 4 instruction §5) — never both required, either
 *       suffices.</li>
 *   <li>Ownership/assignment check — skipped when the permission was
 *       granted via delegation (the delegation check IS the
 *       ownership-equivalent for a guardian) and skipped for SUPER_ADMIN.
 *       Otherwise: if the resource declares an owner, the principal must be
 *       it or be in the assigned set; if the resource declares only an
 *       assigned set (no single owner), the principal must be in it.</li>
 * </ol>
 * This is deliberately NOT {@code if (role == ADMIN)} anywhere — every
 * branch evaluates role, permission, tenant, and resource state together.
 */
@Component
@EnableConfigurationProperties(AuthorizationProperties.class)
public class AuthorizationService implements AuthorizationContext {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final GuardianDelegationChecker guardianDelegationChecker;
    private final AuthorizationProperties properties;

    /**
     * {@code @Lazy} on {@code guardianDelegationChecker} breaks a genuine
     * Spring bean-creation cycle introduced in Day 6: the real
     * implementation chain (GuardianDelegationAdapter -&gt;
     * GuardianAccessService) now depends on {@code AuthorizationContext}
     * itself (GuardianAccessService.grantAccess/revokeAccess are
     * authorized, per Day 6 §8's "granted_by must be an authorized
     * principal"), which Spring resolves back to THIS bean — a cycle
     * invisible to {@code ApplicationModules.verify()} (it only sees the
     * {@code GuardianDelegationChecker} interface type, never the concrete
     * class), but real at wiring time. {@code hasActiveDelegatedAccess}
     * (the only method this class actually calls) never touches that field,
     * so deferring resolution via a lazy proxy is correct, not a workaround
     * that papers over a genuine logical cycle.
     */
    public AuthorizationService(UserRoleRepository userRoleRepository, RoleRepository roleRepository,
                                 RolePermissionRepository rolePermissionRepository,
                                 @org.springframework.context.annotation.Lazy GuardianDelegationChecker guardianDelegationChecker,
                                 AuthorizationProperties properties) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.guardianDelegationChecker = guardianDelegationChecker;
        this.properties = properties;
    }

    @Override
    public AccessDecision can(String permissionCode, ResourceRef resource) {
        UserPrincipal principal = currentPrincipal();
        if (principal == null) {
            return new AccessDecision.Deny("unauthenticated");
        }
        return decide(principal.userId(), permissionCode, resource);
    }

    @Override
    public AccessDecision canAsUser(UUID userId, String permissionCode, ResourceRef resource) {
        return decide(userId, permissionCode, resource);
    }

    private AccessDecision decide(UUID principalUserId, String permissionCode, ResourceRef resource) {
        Instant now = Instant.now();

        if (resource.workflowBlocked()) {
            return new AccessDecision.Deny("invalid_workflow_state");
        }

        Map<UUID, Role> rolesById = roleRepository.findAll().stream()
            .collect(Collectors.toMap(Role::id, r -> r));
        List<UserRole> activeAssignments = userRoleRepository.findByUserId(principalUserId).stream()
            .filter(ur -> ur.isActive(now))
            .toList();

        boolean isSuperAdmin = activeAssignments.stream()
            .anyMatch(ur -> ur.tenantId() == null
                && rolesById.get(ur.roleId()) != null
                && rolesById.get(ur.roleId()).code() == RoleCode.SUPER_ADMIN);

        List<UserRole> relevantAssignments;
        if (isSuperAdmin) {
            relevantAssignments = activeAssignments;
        } else if (resource.tenantId() != null) {
            relevantAssignments = activeAssignments.stream()
                .filter(ur -> resource.tenantId().equals(ur.tenantId()))
                .toList();
            if (relevantAssignments.isEmpty()) {
                return new AccessDecision.Deny("tenant_mismatch");
            }
        } else {
            relevantAssignments = activeAssignments;
        }

        Set<UUID> relevantRoleIds = relevantAssignments.stream().map(UserRole::roleId).collect(Collectors.toSet());
        Set<String> grantedByRole = rolePermissionRepository.findByRoleIdIn(List.copyOf(relevantRoleIds)).stream()
            .map(rp -> rp.permissionCode())
            .collect(Collectors.toSet());
        boolean permissionViaRole = grantedByRole.contains(permissionCode);

        boolean isGuardianInScope = relevantAssignments.stream()
            .anyMatch(ur -> rolesById.get(ur.roleId()) != null && rolesById.get(ur.roleId()).code() == RoleCode.GUARDIAN);
        boolean permissionViaDelegation = false;
        if (!permissionViaRole && isGuardianInScope && resource.delegationTargetStudentId() != null
            && resource.tenantId() != null) {
            permissionViaDelegation = guardianDelegationChecker.hasActiveDelegatedAccess(
                principalUserId, resource.delegationTargetStudentId(), resource.tenantId(), permissionCode, now);
        }

        if (!permissionViaRole && !permissionViaDelegation) {
            return new AccessDecision.Deny("permission_denied");
        }

        if (!permissionViaDelegation && !isSuperAdmin) {
            boolean hasOwner = resource.ownerUserId() != null;
            boolean isOwner = hasOwner && resource.ownerUserId().equals(principalUserId);
            boolean hasAssignment = !resource.assignedUserIds().isEmpty();
            boolean isAssigned = hasAssignment && resource.assignedUserIds().contains(principalUserId);

            if (hasOwner && !isOwner && !isAssigned) {
                return new AccessDecision.Deny("not_owner_or_assigned");
            }
            if (!hasOwner && hasAssignment && !isAssigned) {
                return new AccessDecision.Deny("not_assigned");
            }
        }

        return new AccessDecision.Allow();
    }

    @Override
    public void assertCan(String permissionCode, ResourceRef resource) {
        AccessDecision decision = can(permissionCode, resource);
        if (!decision.isAllowed()) {
            String reason = decision instanceof AccessDecision.Deny d ? d.reasonCode() : "denied";
            throw new AccessDeniedException("Access denied: " + reason);
        }
    }

    @Override
    public boolean hasRecentStepUp() {
        UserPrincipal principal = currentPrincipal();
        if (principal == null || principal.stepUpAt() == null) {
            return false;
        }
        Instant cutoff = Instant.now().minus(properties.getStepUpValidityMinutes(), ChronoUnit.MINUTES);
        return principal.stepUpAt().isAfter(cutoff);
    }

    @Override
    public void assertStepUp() {
        if (!hasRecentStepUp()) {
            throw new AccessDeniedException("Step-up authentication required");
        }
    }

    @Override
    public UUID currentUserId() {
        UserPrincipal principal = currentPrincipal();
        if (principal == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        return principal.userId();
    }

    private UserPrincipal currentPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal;
    }
}
