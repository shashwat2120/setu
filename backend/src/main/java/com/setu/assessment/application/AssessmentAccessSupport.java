package com.setu.assessment.application;

import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Intra-module helper shared by every {@code assessment.application}
 * service — package-private, never exposed cross-module.
 * <p>
 * Day 11 explicit instruction: do NOT repeat Day 10's "faculty assignment
 * bypasses the permission check" idiom. {@link #sectionScoped} instead
 * builds a real {@link ResourceRef#assignedTo}, letting the authorization
 * engine's OWN ownership/assignment check (Day 4, never actually exercised
 * until now — see {@code AuthorizationService.decide}) do the narrowing,
 * while every caller — assigned or not — still goes through a genuine
 * {@code assertCan}/{@code can} permission check. Concretely: if a tenant
 * admin revoked a faculty member's {@code MANAGE_ASSESSMENTS} role-grant
 * tenant-wide (without touching any individual section assignment), Day
 * 9/10's bypass idiom would have WRONGLY still let them through on
 * assignment alone; this idiom correctly denies them, because the
 * permission check is never skipped for anyone.
 * <p>
 * The engine's own logic (`hasAssignment = !assignedUserIds.isEmpty()`)
 * means an EMPTY assigned set makes the ownership check a no-op — so an
 * unassigned caller must never be given an empty set, or the check would
 * silently stop discriminating. {@link #sectionScoped} always supplies a
 * non-empty, single-element set: the caller's own user id when genuinely
 * assigned, or a fresh random id that can never equal it otherwise — from
 * the engine's perspective this is a faithful, minimal proxy for whether
 * the acting principal is validly assigned — the only membership
 * question {@code decide()} ever actually asks.
 */
@Component
class AssessmentAccessSupport {

    private final AcademicDirectory academic;
    private final AuthorizationContext authorizationContext;

    AssessmentAccessSupport(AcademicDirectory academic, AuthorizationContext authorizationContext) {
        this.academic = academic;
        this.authorizationContext = authorizationContext;
    }

    ResourceRef sectionScoped(String resourceType, UUID resourceId, UUID tenantId, UUID sectionId) {
        UUID currentUserId = authorizationContext.currentUserId();
        boolean isAssigned = academic.facultyIdOfUser(tenantId, currentUserId)
            .filter(facultyId -> academic.isActivelyAssigned(sectionId, facultyId))
            .isPresent();
        UUID marker = isAssigned ? currentUserId : UUID.randomUUID();
        return ResourceRef.of(resourceType, resourceId, tenantId).assignedTo(Set.of(marker));
    }

    /** Non-empty only if the CURRENT principal is a student with an active ENROLLED enrollment in this exact section. */
    Optional<UUID> currentEnrolledStudentId(UUID tenantId, UUID sectionId) {
        UUID currentUserId = authorizationContext.currentUserId();
        return academic.studentIdOfUser(tenantId, currentUserId)
            .filter(studentId -> academic.activeEnrollmentId(sectionId, studentId).isPresent());
    }

    /** The current principal's own student-profile id in this tenant. */
    Optional<UUID> currentStudentId(UUID tenantId) {
        return academic.studentIdOfUser(tenantId, authorizationContext.currentUserId());
    }

    /** The identity.users.id that owns a given student-profile id — see {@code LearningProgressService}'s Day 10 precedent for why this is needed for guardian-delegation ownership scoping. */
    Optional<UUID> ownerUserIdOf(UUID studentProfileId) {
        return academic.studentOwner(studentProfileId).map(AcademicDirectory.TenantOwner::ownerUserId);
    }

    Optional<UUID> activeEnrollmentId(UUID sectionId, UUID studentId) {
        return academic.activeEnrollmentId(sectionId, studentId);
    }

    /** The enrollment that can be graded: in force, or completed. See {@link AcademicDirectory#gradableEnrollmentId}. */
    Optional<UUID> gradableEnrollmentId(UUID sectionId, UUID studentId) {
        return academic.gradableEnrollmentId(sectionId, studentId);
    }
}
