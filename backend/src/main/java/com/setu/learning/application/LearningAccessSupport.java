package com.setu.learning.application;

import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.application.FacultyAssignmentService;
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

    private final StudentProfileService studentProfileService;
    private final FacultyProfileService facultyProfileService;
    private final FacultyAssignmentService facultyAssignmentService;
    private final EnrollmentService enrollmentService;
    private final AuthorizationContext authorizationContext;

    LearningAccessSupport(StudentProfileService studentProfileService, FacultyProfileService facultyProfileService,
                           FacultyAssignmentService facultyAssignmentService, EnrollmentService enrollmentService,
                           AuthorizationContext authorizationContext) {
        this.studentProfileService = studentProfileService;
        this.facultyProfileService = facultyProfileService;
        this.facultyAssignmentService = facultyAssignmentService;
        this.enrollmentService = enrollmentService;
        this.authorizationContext = authorizationContext;
    }

    /** Non-empty only if the CURRENT principal is faculty actively assigned to this exact section. */
    Optional<UUID> currentAssignedFacultyId(UUID tenantId, UUID sectionId) {
        UUID currentUserId = authorizationContext.currentUserId();
        return facultyProfileService.findIdByTenantAndOwnerUserId(tenantId, currentUserId)
            .filter(facultyId -> facultyAssignmentService.isActivelyAssigned(sectionId, facultyId));
    }

    /** Non-empty only if the CURRENT principal is a student with an active ENROLLED enrollment in this exact section. */
    Optional<UUID> currentEnrolledStudentId(UUID tenantId, UUID sectionId) {
        UUID currentUserId = authorizationContext.currentUserId();
        return studentProfileService.findIdByTenantAndOwnerUserId(tenantId, currentUserId)
            .filter(studentId -> enrollmentService.findActiveEnrollmentId(sectionId, studentId).isPresent());
    }

    /** The current principal's own student-profile id in this tenant, regardless of any particular section's enrollment — used for "is this MY progress row" ownership checks. */
    Optional<UUID> currentStudentId(UUID tenantId) {
        return studentProfileService.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId());
    }

    /** The identity.users.id that owns a given student-profile id — needed to build a {@code ResourceRef.ownedBy(...)} so the authorization engine's ownership check (not just its permission check) actually excludes every OTHER student, since {@code VIEW_LEARNING_DATA} is broadly role-granted to STUDENT. */
    Optional<UUID> ownerUserIdOf(UUID studentProfileId) {
        return studentProfileService.findTenantAndOwnerUserId(studentProfileId)
            .map(StudentProfileService.StudentTenantOwner::ownerUserId);
    }
}
