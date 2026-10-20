package com.setu.risk.application;

import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.application.FacultyAssignmentService;
import com.setu.shared.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Intra-module helper shared by every {@code risk.application} service —
 * package-private, never exposed cross-module. Same shape as {@code
 * assessment.application.AssessmentAccessSupport}/{@code
 * finance.application.FinanceAccessSupport}: each module keeps its own
 * small access-support helper.
 * <p>
 * <b>"Advisee" definition, disclosed</b>: docs/authorization-matrix.md's
 * "Risk score / factors" and "Recommendation / intervention" rows give
 * FACULTY access to "Own advisees" — and the canonical schema does have a
 * dedicated {@code ADVISOR} role on {@code enrollment.class_faculty}
 * (V27). But that table (and {@code enrollment.classes}/{@code
 * class_students} it depends on) has never been populated by any prior
 * day's work — {@code ClassTenantRef}'s own javadoc states plainly that
 * building actual Class CRUD/membership was explicitly deferred out of
 * Day 7 scope and no later day picked it up. A table that can never hold
 * a row is not a usable authorization source; building full Class
 * CRUD/membership just to populate it would be a large, unrelated feature
 * that belongs to {@code enrollment}, not this day's Student Success
 * scope.
 * <p>
 * The smallest defensible resolution, reusing 100% existing, already-
 * populated infrastructure: a faculty member's "advisees" are the
 * students actively enrolled ({@link EnrollmentStatus#ENROLLED}) in any
 * course section that faculty member is actively assigned to ({@link
 * FacultyAssignmentService#isActivelyAssigned}) — the exact same
 * faculty-to-student relationship Days 9–11 already used to scope
 * attendance/assessment/LMS access. This is disclosed here rather than
 * silently assumed.
 */
@Component
class RiskAccessSupport {

    private final StudentProfileService studentProfileService;
    private final FacultyProfileService facultyProfileService;
    private final EnrollmentService enrollmentService;
    private final FacultyAssignmentService facultyAssignmentService;
    private final AuthorizationContext authorizationContext;

    RiskAccessSupport(StudentProfileService studentProfileService, FacultyProfileService facultyProfileService,
                       EnrollmentService enrollmentService, FacultyAssignmentService facultyAssignmentService,
                       AuthorizationContext authorizationContext) {
        this.studentProfileService = studentProfileService;
        this.facultyProfileService = facultyProfileService;
        this.enrollmentService = enrollmentService;
        this.facultyAssignmentService = facultyAssignmentService;
        this.authorizationContext = authorizationContext;
    }

    /** The current principal's own student-profile id in this tenant, if any. */
    Optional<UUID> currentStudentId(UUID tenantId) {
        return studentProfileService.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId());
    }

    /**
     * True only if the current principal is a faculty member (in this
     * tenant) actively assigned to a section the given student is
     * currently ENROLLED in — see class javadoc for why this is the
     * "advisee" definition. {@code studentId}'s enrollments are read via
     * {@link EnrollmentService#listActiveEnrollmentRefsForStudent} — a
     * no-auth narrow port, deliberately NOT {@code listForStudent} (that
     * method enforces its own self-or-broad-permission check, which a
     * plain advising faculty member does not hold; see that port's
     * javadoc for the full reasoning).
     */
    boolean currentUserIsAdvisorOf(UUID tenantId, UUID studentId) {
        Optional<UUID> facultyId = facultyProfileService.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId());
        if (facultyId.isEmpty() || !authorizationContext.holdsFacultyRole(tenantId)) {
            return false;
        }
        return enrollmentService.listActiveEnrollmentRefsForStudent(studentId).stream()
            .anyMatch(e -> facultyAssignmentService.isActivelyAssigned(e.sectionId(), facultyId.get()));
    }
}
