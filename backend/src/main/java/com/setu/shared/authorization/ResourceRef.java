package com.setu.shared.authorization;

import java.util.Set;
import java.util.UUID;

/**
 * Everything {@link AuthorizationContext#can} needs to know about the
 * resource being accessed, already resolved by the calling module from its
 * own entities — the authorization engine never reaches into another
 * module's tables to figure this out itself (that would violate the module
 * boundary the engine exists to enforce in the first place).
 *
 * @param resourceType             e.g. "student_profile", "enrollment", "course_section" — used only for audit context, not for dispatch logic
 * @param resourceId               the specific instance being accessed
 * @param tenantId                 null for platform-scoped resources; otherwise the owning college's id
 * @param ownerUserId              the identity.users.id who directly owns this resource (e.g. a student viewing their own record), or null if ownership doesn't apply
 * @param assignedUserIds          identity.users.id values with an explicit assignment to this resource (e.g. faculty assigned to a section) — checked as a fallback when ownerUserId doesn't match or isn't set
 * @param delegationTargetStudentId when non-null, this resource belongs to a student who can delegate access to guardians — enables the guardian delegation path (see GuardianDelegationChecker) instead of (or alongside) direct ownership
 * @param workflowBlocked          true if the resource's own domain/state-machine logic has already determined this action is disallowed in the resource's current state — the engine denies unconditionally when true, regardless of permission/ownership (Day 4 instruction §7)
 */
public record ResourceRef(
    String resourceType,
    UUID resourceId,
    UUID tenantId,
    UUID ownerUserId,
    Set<UUID> assignedUserIds,
    UUID delegationTargetStudentId,
    boolean workflowBlocked
) {

    public static ResourceRef of(String resourceType, UUID resourceId, UUID tenantId) {
        return new ResourceRef(resourceType, resourceId, tenantId, null, Set.of(), null, false);
    }

    public ResourceRef ownedBy(UUID ownerUserId) {
        return new ResourceRef(resourceType, resourceId, tenantId, ownerUserId, assignedUserIds, delegationTargetStudentId, workflowBlocked);
    }

    public ResourceRef assignedTo(Set<UUID> assignedUserIds) {
        return new ResourceRef(resourceType, resourceId, tenantId, ownerUserId, assignedUserIds, delegationTargetStudentId, workflowBlocked);
    }

    public ResourceRef delegatableForStudent(UUID studentProfileId) {
        return new ResourceRef(resourceType, resourceId, tenantId, ownerUserId, assignedUserIds, studentProfileId, workflowBlocked);
    }

    public ResourceRef withWorkflowBlocked(boolean blocked) {
        return new ResourceRef(resourceType, resourceId, tenantId, ownerUserId, assignedUserIds, delegationTargetStudentId, blocked);
    }
}
