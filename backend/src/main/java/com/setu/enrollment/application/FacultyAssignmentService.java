package com.setu.enrollment.application;

import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.FacultyAssignment;
import com.setu.enrollment.domain.FacultyAssignmentRole;
import com.setu.enrollment.infrastructure.CourseSectionRepository;
import com.setu.enrollment.infrastructure.FacultyAssignmentRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.directory.FacultyDirectory;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Faculty-to-section assignments (Day 7 §9). No docs/state-machines.md
 * entry exists for this entity (see {@link FacultyAssignment}'s javadoc) —
 * history is preserved by ending an assignment rather than deleting it.
 * Faculty tenant validation goes through the shared {@link FacultyDirectory}
 * port (implemented by directory.FacultyProfileService), per the explicit
 * Day 7 STOP-and-redesign directive — never
 * {@code directory.infrastructure.FacultyProfileRepository} directly. See
 * {@code shared.directory}'s package-info for the full cycle-avoidance
 * justification.
 * <p>
 * {@code @NamedInterface} on this class (Day 9 §15 ceremony): caller is
 * {@code attendance.application}, which must verify the current user is the
 * actively-assigned faculty for a section before allowing them to open/
 * close an attendance session for it, or to correct a record, or to
 * subscribe to that section's live board. Only {@link #isActivelyAssigned}
 * is exposed — a bare boolean, not the {@link FacultyAssignment} entity, no
 * authorization check of its own (the caller authorizes its own operation
 * using this as one input). No reverse edge from enrollment into attendance
 * exists. Verified via {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class FacultyAssignmentService {

    private static final String MANAGE_PERMISSION = "MANAGE_FACULTY_ASSIGNMENTS";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final FacultyAssignmentRepository facultyAssignmentRepository;
    private final CourseSectionRepository courseSectionRepository;
    private final FacultyDirectory facultyDirectory;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public FacultyAssignmentService(FacultyAssignmentRepository facultyAssignmentRepository,
                                     CourseSectionRepository courseSectionRepository, FacultyDirectory facultyDirectory,
                                     AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.facultyAssignmentRepository = facultyAssignmentRepository;
        this.courseSectionRepository = courseSectionRepository;
        this.facultyDirectory = facultyDirectory;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface AssignResult {
        record Success(UUID assignmentId) implements AssignResult {
        }

        record SectionNotFound() implements AssignResult {
        }

        record FacultyNotFound() implements AssignResult {
        }

        record TenantMismatch() implements AssignResult {
        }

        record InvalidAssignmentData(String message) implements AssignResult {
        }

        /** V30's own partial unique index: only one active (ends_at IS NULL) PRIMARY assignment per section. */
        record PrimaryAlreadyAssigned() implements AssignResult {
        }
    }

    @Transactional
    public AssignResult assign(UUID sectionId, UUID facultyId, FacultyAssignmentRole role, BigDecimal allocationPct,
                                LocalDate startsAt, LocalDate endsAt) {
        Optional<CourseSection> section = courseSectionRepository.findById(sectionId);
        if (section.isEmpty()) {
            return new AssignResult.SectionNotFound();
        }
        UUID tenantId = section.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("faculty_assignment", UUID.randomUUID(), tenantId));

        Optional<UUID> facultyTenantId = facultyDirectory.findTenantId(facultyId);
        if (facultyTenantId.isEmpty()) {
            return new AssignResult.FacultyNotFound();
        }
        if (!facultyTenantId.get().equals(tenantId)) {
            return new AssignResult.TenantMismatch();
        }

        if (role == FacultyAssignmentRole.PRIMARY
            && facultyAssignmentRepository.findBySectionIdAndRoleAndEndsAtIsNull(sectionId, FacultyAssignmentRole.PRIMARY).isPresent()) {
            return new AssignResult.PrimaryAlreadyAssigned();
        }

        UUID actor = authorizationContext.currentUserId();
        FacultyAssignment assignment;
        try {
            assignment = new FacultyAssignment(UUID.randomUUID(), tenantId, sectionId, facultyId, role, allocationPct,
                startsAt, endsAt);
        } catch (IllegalArgumentException e) {
            return new AssignResult.InvalidAssignmentData(e.getMessage());
        }
        facultyAssignmentRepository.save(assignment);
        auditLogWriter.write(tenantId, actor, "FACULTY_ASSIGNMENT_CREATED", "enrollment.faculty_assignments",
            assignment.id(), Map.of("sectionId", sectionId, "facultyId", facultyId, "role", role.name()));
        return new AssignResult.Success(assignment.id());
    }

    @Transactional(readOnly = true)
    public Optional<FacultyAssignment> get(UUID assignmentId) {
        Optional<FacultyAssignment> assignment = facultyAssignmentRepository.findById(assignmentId);
        assignment.ifPresent(a -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("faculty_assignment", a.id(), a.tenantId())));
        return assignment;
    }

    @Transactional(readOnly = true)
    public List<FacultyAssignment> listForSection(UUID sectionId, UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("faculty_assignment", UUID.randomUUID(), tenantId));
        return facultyAssignmentRepository.findBySectionId(sectionId);
    }

    /**
     * Day 16 §faculty: self-view is always allowed (a faculty member sees their own assignments with no admin
     * permission needed, same idiom as {@code StudentProfileService#get}/{@code FacultyProfileService#get});
     * anyone else needs {@code VIEW_TENANT_DATA}. Backs the Faculty dashboard's "my sections" — the frontend
     * never supplies its own facultyId as a trusted value beyond what this check re-verifies server-side.
     */
    @Transactional(readOnly = true)
    public List<FacultyAssignment> listForFaculty(UUID facultyId, UUID tenantId) {
        UUID ownFacultyId = facultyDirectory.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId())
            .orElse(null);
        boolean isSelf = facultyId.equals(ownFacultyId);
        if (!isSelf) {
            authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("faculty_assignment", UUID.randomUUID(), tenantId));
        }
        return facultyAssignmentRepository.findByFacultyId(facultyId);
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidAssignmentData(String message) implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID assignmentId, BigDecimal allocationPct, LocalDate startsAt) {
        Optional<FacultyAssignment> maybeAssignment = facultyAssignmentRepository.findById(assignmentId);
        if (maybeAssignment.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        FacultyAssignment assignment = maybeAssignment.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("faculty_assignment", assignment.id(), assignment.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        try {
            assignment.updateAllocation(allocationPct, startsAt);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidAssignmentData(e.getMessage());
        }
        facultyAssignmentRepository.save(assignment);
        auditLogWriter.write(assignment.tenantId(), actor, "FACULTY_ASSIGNMENT_UPDATED", "enrollment.faculty_assignments",
            assignment.id(), Map.of("allocationPct", String.valueOf(allocationPct)));
        return new UpdateResult.Success();
    }

    public sealed interface EndResult {
        record Success() implements EndResult {
        }

        record NotFound() implements EndResult {
        }

        record InvalidEndDate(String message) implements EndResult {
        }
    }

    @Transactional
    public EndResult end(UUID assignmentId, LocalDate endsAt) {
        Optional<FacultyAssignment> maybeAssignment = facultyAssignmentRepository.findById(assignmentId);
        if (maybeAssignment.isEmpty()) {
            return new EndResult.NotFound();
        }
        FacultyAssignment assignment = maybeAssignment.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("faculty_assignment", assignment.id(), assignment.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        try {
            assignment.end(endsAt);
        } catch (IllegalArgumentException e) {
            return new EndResult.InvalidEndDate(e.getMessage());
        }
        facultyAssignmentRepository.save(assignment);
        auditLogWriter.write(assignment.tenantId(), actor, "FACULTY_ASSIGNMENT_REMOVED", "enrollment.faculty_assignments",
            assignment.id(), Map.of("endsAt", String.valueOf(endsAt)));
        return new EndResult.Success();
    }

    /** See this class's javadoc for the full Day 9 §15 cross-module justification. */
    @Transactional(readOnly = true)
    public boolean isActivelyAssigned(UUID sectionId, UUID facultyId) {
        return facultyAssignmentRepository.existsBySectionIdAndFacultyIdAndEndsAtIsNull(sectionId, facultyId);
    }
}
