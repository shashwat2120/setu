package com.setu.shared.authorization;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The real {@code can(principal, permission, resource, action)} engine (Day 4 instruction §10), as a library every service embeds. Where
 * roles come from is the only thing that differs between services, so that is the {@link RoleGrantSource}; the decision is the same
 * everywhere. Decision algorithm, in order; the first matching rule decides:
 * <ol>
 *   <li>Unauthenticated: deny.</li>
 *   <li>Resource flagged {@code workflowBlocked} by its own domain logic: deny unconditionally, before any role or ownership check runs
 *       (permission alone is insufficient, Day 4 instruction §7).</li>
 *   <li>Tenant check: a tenant-scoped resource requires an ACTIVE tenant-scoped role in that EXACT tenant, unless the principal holds an
 *       active platform-scoped SUPER_ADMIN role. The tenant used is always the resource's own {@code tenantId}, resolved server-side by
 *       the module that built the {@link ResourceRef} from its own entity, never a client-supplied value (Day 4 instruction §3).</li>
 *   <li>Permission check: granted either by one of the principal's tenant-matching roles OR, for a resource naming a delegation target
 *       student, by an active guardian delegation (Day 4 instruction §5). Either suffices.</li>
 *   <li>Ownership/assignment check, skipped when the permission came by delegation (the delegation IS the ownership-equivalent for a
 *       guardian) and for SUPER_ADMIN. Otherwise: if the resource declares an owner, the principal must be it or be in the assigned set;
 *       if it declares only an assigned set, the principal must be in it.</li>
 * </ol>
 * Deliberately NOT {@code if (role == ADMIN)} anywhere: every branch evaluates role, permission, tenant and resource state together.
 * <p>
 * When there is no {@link GuardianDelegationChecker} in the process (Identity runs without the guardian-access module) delegation is
 * denied, never assumed.
 */
public class AuthorizationEngine implements AuthorizationContext {

    private final RoleGrantSource roles;
    private final ObjectProvider<GuardianDelegationChecker> guardianDelegationChecker;
    private final long stepUpValidityMinutes;

    /**
     * {@code ObjectProvider} on the delegation checker (resolved only when called) breaks a genuine bean-creation cycle: the real checker
     * chain (GuardianDelegationAdapter -> GuardianAccessService) depends on {@code AuthorizationContext} itself, because granting and
     * revoking access are authorized operations. The cycle is invisible to the type graph, not to wiring.
     */
    public AuthorizationEngine(RoleGrantSource roles, ObjectProvider<GuardianDelegationChecker> guardianDelegationChecker,
                               long stepUpValidityMinutes) {
        this.roles = roles;
        this.guardianDelegationChecker = guardianDelegationChecker;
        this.stepUpValidityMinutes = stepUpValidityMinutes;
    }

    @Override
    public AccessDecision can(String permissionCode, ResourceRef resource) {
        AuthenticatedUser principal = currentPrincipal();
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

        List<RoleGrant> activeAssignments = roles.activeGrants(principalUserId, now);

        boolean isSuperAdmin = activeAssignments.stream()
            .anyMatch(g -> g.tenantId() == null && RoleGrant.SUPER_ADMIN.equals(g.roleCode()));

        List<RoleGrant> relevantAssignments;
        if (isSuperAdmin) {
            relevantAssignments = activeAssignments;
        } else if (resource.tenantId() != null) {
            relevantAssignments = activeAssignments.stream()
                .filter(g -> resource.tenantId().equals(g.tenantId()))
                .toList();
            if (relevantAssignments.isEmpty()) {
                return new AccessDecision.Deny("tenant_mismatch");
            }
        } else {
            relevantAssignments = activeAssignments;
        }

        Set<String> relevantRoleCodes = relevantAssignments.stream().map(RoleGrant::roleCode).collect(Collectors.toSet());
        boolean permissionViaRole = roles.permissionsOf(relevantRoleCodes).contains(permissionCode);

        boolean isGuardianInScope = relevantAssignments.stream().anyMatch(g -> RoleGrant.GUARDIAN.equals(g.roleCode()));
        boolean permissionViaDelegation = false;
        if (!permissionViaRole && isGuardianInScope && resource.delegationTargetStudentId() != null
            && resource.tenantId() != null) {
            GuardianDelegationChecker checker = guardianDelegationChecker.getIfAvailable();
            permissionViaDelegation = checker != null && checker.hasActiveDelegatedAccess(
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
        AuthenticatedUser principal = currentPrincipal();
        if (principal == null || principal.stepUpAt() == null) {
            return false;
        }
        Instant cutoff = Instant.now().minus(stepUpValidityMinutes, ChronoUnit.MINUTES);
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
        AuthenticatedUser principal = currentPrincipal();
        if (principal == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        return principal.userId();
    }

    private AuthenticatedUser currentPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            return null;
        }
        return principal;
    }
}
