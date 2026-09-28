package com.setu.learning.application;

import com.setu.learning.domain.Assignment;
import com.setu.learning.domain.AssignmentStatus;
import com.setu.learning.infrastructure.AssignmentRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 10 §5: assignments. Per the authorization matrix, COLLEGE_ADMIN is
 * capped at a tenant-wide read-only ceiling here — genuinely lower than LMS
 * content's admin row — so writes are assigned-faculty-only, falling back
 * to {@code MANAGE_ASSIGNMENTS} (granted to SUPER_ADMIN only, see V64) for
 * the platform/admin path, never a COLLEGE_ADMIN bypass. Admin/faculty read
 * reuses {@code VIEW_TENANT_DATA}.
 * <p>
 * {@code @NamedInterface} added Day 14: {@link #listPublishedForSection}
 * gives {@code aiexplain.application}'s {@code getUpcomingDeadlines} tool
 * every student-visible assignment for a section - no authorization of
 * its own, since the caller has already authorized the target student
 * before reaching here, the same narrow-port precedent as Day 13.
 */
@org.springframework.modulith.NamedInterface
@Service
public class AssignmentService {

    private static final String MANAGE_PERMISSION = "MANAGE_ASSIGNMENTS";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final AssignmentRepository assignmentRepository;
    private final CourseSectionDirectory courseSectionDirectory;
    private final LearningAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public AssignmentService(AssignmentRepository assignmentRepository, CourseSectionDirectory courseSectionDirectory,
                              LearningAccessSupport accessSupport, AuthorizationContext authorizationContext,
                              AuditLogWriter auditLogWriter) {
        this.assignmentRepository = assignmentRepository;
        this.courseSectionDirectory = courseSectionDirectory;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID assignmentId) implements CreateResult {
        }

        record SectionNotFound() implements CreateResult {
        }

        record InvalidAssignmentData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID sectionId, String title, BigDecimal maxMarks, Instant dueAt, String latePolicyJson) {
        Optional<UUID> tenantId = courseSectionDirectory.findTenantId(sectionId);
        if (tenantId.isEmpty()) {
            return new CreateResult.SectionNotFound();
        }
        assertManage(sectionId, tenantId.get());

        Assignment assignment;
        try {
            assignment = new Assignment(UUID.randomUUID(), tenantId.get(), sectionId, title, maxMarks, dueAt, latePolicyJson);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidAssignmentData(e.getMessage());
        }
        assignmentRepository.save(assignment);
        auditLogWriter.write(tenantId.get(), authorizationContext.currentUserId(), "ASSIGNMENT_CREATED",
            "learning.assignments", assignment.id(), Map.of("sectionId", sectionId, "title", title));
        return new CreateResult.Success(assignment.id());
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
    public UpdateResult update(UUID assignmentId, String title, BigDecimal maxMarks, Instant dueAt, String latePolicyJson) {
        Optional<Assignment> maybeAssignment = assignmentRepository.findById(assignmentId);
        if (maybeAssignment.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        Assignment assignment = maybeAssignment.get();
        assertManage(assignment.sectionId(), assignment.tenantId());
        try {
            assignment.update(title, maxMarks, dueAt, latePolicyJson);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidAssignmentData(e.getMessage());
        }
        assignmentRepository.save(assignment);
        auditLogWriter.write(assignment.tenantId(), authorizationContext.currentUserId(), "ASSIGNMENT_UPDATED",
            "learning.assignments", assignment.id(), Map.of("title", title));
        return new UpdateResult.Success();
    }

    public enum TransitionResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public TransitionResult publish(UUID assignmentId) {
        return transition(assignmentId, AssignmentStatus.PUBLISHED);
    }

    /** Row-locked via {@code findByIdForUpdate} — serializes against a concurrent {@code AssignmentSubmissionService.submit} on the same assignment (Day 10 concurrency requirement 4). */
    @Transactional
    public TransitionResult close(UUID assignmentId) {
        Optional<Assignment> maybeAssignment = assignmentRepository.findByIdForUpdate(assignmentId);
        if (maybeAssignment.isEmpty()) {
            return TransitionResult.NOT_FOUND;
        }
        Assignment assignment = maybeAssignment.get();
        assertManage(assignment.sectionId(), assignment.tenantId());
        if (!assignment.canTransitionTo(AssignmentStatus.CLOSED)) {
            return TransitionResult.INVALID_TRANSITION;
        }
        assignment.transitionTo(AssignmentStatus.CLOSED);
        assignmentRepository.save(assignment);
        auditLogWriter.write(assignment.tenantId(), authorizationContext.currentUserId(), "ASSIGNMENT_UPDATED",
            "learning.assignments", assignment.id(), Map.of("status", "CLOSED"));
        return TransitionResult.SUCCESS;
    }

    private TransitionResult transition(UUID assignmentId, AssignmentStatus target) {
        Optional<Assignment> maybeAssignment = assignmentRepository.findById(assignmentId);
        if (maybeAssignment.isEmpty()) {
            return TransitionResult.NOT_FOUND;
        }
        Assignment assignment = maybeAssignment.get();
        assertManage(assignment.sectionId(), assignment.tenantId());
        if (!assignment.canTransitionTo(target)) {
            return TransitionResult.INVALID_TRANSITION;
        }
        assignment.transitionTo(target);
        assignmentRepository.save(assignment);
        auditLogWriter.write(assignment.tenantId(), authorizationContext.currentUserId(), "ASSIGNMENT_UPDATED",
            "learning.assignments", assignment.id(), Map.of("status", target.name()));
        return TransitionResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public Optional<Assignment> get(UUID assignmentId) {
        Optional<Assignment> assignment = assignmentRepository.findById(assignmentId);
        if (assignment.isEmpty()) {
            return Optional.empty();
        }
        Assignment a = assignment.get();
        boolean isFacultyOrAdmin = assertRead(a.sectionId(), a.tenantId());
        if (a.status() == AssignmentStatus.DRAFT && !isFacultyOrAdmin) {
            return Optional.empty();
        }
        return assignment;
    }

    @Transactional(readOnly = true)
    public List<Assignment> listForSection(UUID sectionId, UUID tenantId) {
        boolean isFacultyOrAdmin = assertRead(sectionId, tenantId);
        List<Assignment> all = assignmentRepository.findBySectionId(sectionId);
        if (isFacultyOrAdmin) {
            return all;
        }
        return all.stream().filter(a -> a.status() != AssignmentStatus.DRAFT).toList();
    }

    /** Day 14 cross-module port - see class javadoc. Every student-visible (non-DRAFT) assignment for a section, no authorization of its own. */
    @Transactional(readOnly = true)
    public List<Assignment> listPublishedForSection(UUID sectionId) {
        return assignmentRepository.findBySectionId(sectionId).stream()
            .filter(a -> a.status() != AssignmentStatus.DRAFT)
            .toList();
    }

    /** Package-private: {@link AssignmentSubmissionService} needs the same assigned-faculty-or-platform-admin check for authorizing a submission-listing/close-adjacent read, and direct repository access for the submit flow's own row lock. */
    Assignment findForUpdateOrThrow(UUID assignmentId) {
        return assignmentRepository.findByIdForUpdate(assignmentId)
            .orElseThrow(() -> new IllegalArgumentException("assignment not found"));
    }

    Optional<Assignment> findById(UUID assignmentId) {
        return assignmentRepository.findById(assignmentId);
    }

    private void assertManage(UUID sectionId, UUID tenantId) {
        if (accessSupport.currentAssignedFacultyId(tenantId, sectionId).isPresent()) {
            return;
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("assignment", UUID.randomUUID(), tenantId));
    }

    /** Returns true when the caller is assigned faculty or a permitted admin (draft assignments stay visible to them). */
    private boolean assertRead(UUID sectionId, UUID tenantId) {
        if (accessSupport.currentAssignedFacultyId(tenantId, sectionId).isPresent()) {
            return true;
        }
        if (accessSupport.currentEnrolledStudentId(tenantId, sectionId).isPresent()) {
            return false;
        }
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("assignment", UUID.randomUUID(), tenantId));
        return true;
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID sectionId, String title, java.math.BigDecimal maxMarks, java.time.Instant dueAt, String latePolicyJson) {
        return switch (create(sectionId, title, maxMarks, dueAt, latePolicyJson)) {
            case CreateResult.Success s -> s.assignmentId();
            default -> throw new IllegalStateException("seed assignment creation failed: " + title);
        };
    }

}
