package com.setu.aifacts.application;

import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.application.FacultyAssignmentService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.tenant.TenantContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Day 14 §AI: the AI layer's own authorization gate — deliberately NOT a
 * datasource of its own (AGENTS.md: "the ai-explain module never gets a
 * datasource"), only calls into other modules' already-established,
 * no-auth {@code @NamedInterface} ports ({@link StudentProfileService},
 * {@link FacultyProfileService}, {@link EnrollmentService}, {@link
 * FacultyAssignmentService}) plus the shared {@link AuthorizationContext}
 * — the exact same pieces {@code risk.application.RiskAccessSupport} is
 * built from, reproduced here rather than reused across module
 * boundaries, matching that class's own documented precedent ("each
 * module keeps its own small access-support helper").
 * <p>
 * Every fact in {@link AiFactsService} calls {@link #assertView} before
 * touching any per-student data — the model NEVER decides authorization;
 * it only supplies which student it is asking about (or omits it, meaning
 * "the caller themselves"), and this class is the single place that
 * decision is actually made, in application code, every time.
 */
@Component
class AiAccessSupport {

    private static final String ADMIN_PERMISSION = "VIEW_TENANT_DATA";

    private final StudentProfileService studentProfileService;
    private final FacultyProfileService facultyProfileService;
    private final EnrollmentService enrollmentService;
    private final FacultyAssignmentService facultyAssignmentService;
    private final AuthorizationContext authorizationContext;
    private final TenantContext tenantContext;

    AiAccessSupport(StudentProfileService studentProfileService, FacultyProfileService facultyProfileService,
                     EnrollmentService enrollmentService, FacultyAssignmentService facultyAssignmentService,
                     AuthorizationContext authorizationContext, TenantContext tenantContext) {
        this.studentProfileService = studentProfileService;
        this.facultyProfileService = facultyProfileService;
        this.enrollmentService = enrollmentService;
        this.facultyAssignmentService = facultyAssignmentService;
        this.authorizationContext = authorizationContext;
        this.tenantContext = tenantContext;
    }

    /** The current principal's own student-profile id in this tenant, if any. */
    Optional<UUID> currentStudentId(UUID tenantId) {
        return studentProfileService.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId());
    }

    boolean currentUserIsAdvisorOf(UUID tenantId, UUID studentId) {
        Optional<UUID> facultyId = facultyProfileService.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId());
        if (facultyId.isEmpty() || !authorizationContext.holdsFacultyRole(tenantId)) {
            return false;
        }
        return enrollmentService.listActiveEnrollmentRefsForStudent(studentId).stream()
            .anyMatch(e -> facultyAssignmentService.isActivelyAssigned(e.sectionId(), facultyId.get()));
    }

    record TargetStudent(UUID tenantId, UUID studentId, UUID ownerUserId) {
    }

    /**
     * Resolves which student the tool call is actually about: the given
     * {@code studentIdParam} if present (a candidate target, not yet
     * trusted), otherwise the CALLER'S OWN student profile, found via
     * {@link TenantContext} (the caller's own tenant, resolved from their
     * authenticated session, never a prompt/client-supplied value) plus
     * {@link StudentProfileService#findIdByTenantAndOwnerUserId}. Either
     * way, the returned id is then subject to the exact same {@link
     * #assertView} check before any data is fetched — supplying an id
     * never bypasses authorization, it only says who the question is
     * about.
     */
    Optional<TargetStudent> resolveTargetStudent(String studentIdParam) {
        if (isNoStudentGiven(studentIdParam)) {
            Optional<UUID> tenantId = tenantContext.tenantId();
            if (tenantId.isEmpty()) {
                return Optional.empty();
            }
            UUID currentUserId = authorizationContext.currentUserId();
            return studentProfileService.findIdByTenantAndOwnerUserId(tenantId.get(), currentUserId)
                .map(studentId -> new TargetStudent(tenantId.get(), studentId, currentUserId));
        }
        UUID studentId;
        try {
            studentId = UUID.fromString(studentIdParam.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return studentProfileService.findTenantAndOwnerUserId(studentId)
            .map(info -> new TargetStudent(info.tenantId(), studentId, info.ownerUserId()));
    }

    /**
     * No student given: nothing, blank, or one of the words a model writes into an optional argument it was told to leave out ("None", "null", "undefined", "N/A",
     * "me", "self"). Some hosted models do this, and a student id that is not an id would otherwise be refused as an invalid target even though the question was
     * plainly about the caller. It only ever widens "no student" to the CALLER'S OWN profile, the narrowest scope there is, and never to anyone else.
     */
    static boolean isNoStudentGiven(String studentIdParam) {
        if (studentIdParam == null || studentIdParam.isBlank()) {
            return true;
        }
        return switch (studentIdParam.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none", "null", "undefined", "n/a", "na", "me", "self", "myself", "current", "caller" -> true;
            default -> false;
        };
    }

    /**
     * Own / advisor (faculty actively assigned to a section the student is
     * ENROLLED in) / admin ({@code VIEW_TENANT_DATA}) / guardian (an
     * active delegation for {@code guardianPermissionCode}, scoped to
     * exactly this student). Throws {@link AccessDeniedException} — never
     * returns a partial or silently-empty result — so a denied AI tool
     * call surfaces as a genuine authorization failure, not a confusing
     * "no data found" the caller might mistake for the student simply
     * having no attendance/grades/etc.
     */
    void assertView(UUID tenantId, UUID studentId, UUID ownerUserId, String guardianPermissionCode) {
        Optional<UUID> ownStudentId = currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            return;
        }
        if (currentUserIsAdvisorOf(tenantId, studentId)) {
            return;
        }
        if (authorizationContext.can(ADMIN_PERMISSION, ResourceRef.of("ai_tool_data", studentId, tenantId)).isAllowed()) {
            return;
        }
        ResourceRef delegated = ResourceRef.of("ai_tool_data", studentId, tenantId)
            .ownedBy(ownerUserId).delegatableForStudent(studentId);
        authorizationContext.assertCan(guardianPermissionCode, delegated);
    }

    /** Same shape as {@link #assertView} but with no guardian-delegation branch at all — for tools (career/skill-gap) the authorization matrix gives guardians no access to whatsoever. */
    void assertViewNoGuardian(UUID tenantId, UUID studentId) {
        Optional<UUID> ownStudentId = currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            return;
        }
        if (currentUserIsAdvisorOf(tenantId, studentId)) {
            return;
        }
        authorizationContext.assertCan(ADMIN_PERMISSION, ResourceRef.of("ai_tool_data", studentId, tenantId));
    }
}
