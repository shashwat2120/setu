package com.setu.learning.application;

import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Intra-module helper shared by every {@code learning.application} service
 * — package-private, never exposed cross-module. Wraps the exact
 * assigned-faculty-or-admin / enrolled-student idiom Day 9 established for
 * attendance (own assigned sections for faculty, active ENROLLED status for
 * students — see {@code EnrollmentService.findActiveEnrollmentId}'s
 * javadoc), applied uniformly across modules/lessons/resources/progress/
 * assignments/submissions rather than duplicated six times.
 */
@Component
class LearningAccessSupport {

    private final AcademicDirectory academic;
    private final AuthorizationContext authorizationContext;

    LearningAccessSupport(AcademicDirectory academic, AuthorizationContext authorizationContext) {
        this.academic = academic;
        this.authorizationContext = authorizationContext;
    }

    /** Non-empty only if the CURRENT principal is faculty actively assigned to this exact section and still holds the faculty role. */
    Optional<UUID> currentAssignedFacultyId(UUID tenantId, UUID sectionId) {
        UUID currentUserId = authorizationContext.currentUserId();
        return academic.facultyIdOfUser(tenantId, currentUserId)
            .filter(facultyId -> academic.isActivelyAssigned(sectionId, facultyId))
            // assigned AND still faculty: an assignment that outlived a role change opens nothing
            .filter(facultyId -> authorizationContext.holdsFacultyRole(tenantId));
    }

    /** Non-empty only if the CURRENT principal is a student with an active ENROLLED enrollment in this exact section. */
    Optional<UUID> currentEnrolledStudentId(UUID tenantId, UUID sectionId) {
        UUID currentUserId = authorizationContext.currentUserId();
        return academic.studentIdOfUser(tenantId, currentUserId)
            .filter(studentId -> academic.activeEnrollmentId(sectionId, studentId).isPresent());
    }

    /**
     * Non-empty only if the CURRENT principal is a student who may <b>read</b> this section's content: enrolled now, or enrolled and finished (a final grade
     * completed the enrollment). A finished course stays readable to the student who took it. Reading only: submitting work and recording progress still use
     * {@link #currentEnrolledStudentId}, so nothing can be added to a course that is over, and a student who dropped has neither.
     */
    Optional<UUID> currentViewingStudentId(UUID tenantId, UUID sectionId) {
        UUID currentUserId = authorizationContext.currentUserId();
        return academic.studentIdOfUser(tenantId, currentUserId)
            .filter(studentId -> academic.gradableEnrollmentId(sectionId, studentId).isPresent());
    }

    /** The current principal's own student-profile id in this tenant, regardless of any particular section's enrollment — used for "is this MY progress row" ownership checks. */
    Optional<UUID> currentStudentId(UUID tenantId) {
        return academic.studentIdOfUser(tenantId, authorizationContext.currentUserId());
    }

    /** The identity.users.id that owns a given student-profile id — needed to build a {@code ResourceRef.ownedBy(...)} so the authorization engine's ownership check (not just its permission check) actually excludes every OTHER student, since {@code VIEW_LEARNING_DATA} is broadly role-granted to STUDENT. */
    Optional<UUID> ownerUserIdOf(UUID studentProfileId) {
        return academic.studentOwner(studentProfileId).map(AcademicDirectory.TenantOwner::ownerUserId);
    }
}
