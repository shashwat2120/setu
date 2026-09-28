package com.setu.learning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.learning.domain.Assignment;
import com.setu.learning.domain.AssignmentStatus;
import com.setu.learning.domain.AssignmentSubmission;
import com.setu.learning.domain.AssignmentSubmissionStatus;
import com.setu.learning.infrastructure.AssignmentSubmissionInsertGuard;
import com.setu.learning.infrastructure.AssignmentSubmissionRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 10 §6: assignment_submissions — the authoritative student submit
 * flow. {@code attemptNo} is always SERVER-computed, never client-supplied
 * — "validate attempt number" is true by construction. Day 10 never
 * implements resubmission (the only documented path to a second attempt,
 * docs/state-machines.md #16's {@code GRADED -> RESUBMISSION_REQUESTED},
 * requires the grading workflow — explicitly Day 11 scope), so a student
 * with ANY existing submission is rejected outright rather than silently
 * issued attempt 2; every accepted submission is attempt 1. Rows are
 * created directly at {@code SUBMITTED}/{@code LATE} (never a lingering
 * {@code DRAFT} row — no "save draft" endpoint was requested), and this
 * module never writes {@code grade_id} or transitions a row to {@code
 * GRADED}/{@code RESUBMISSION_REQUESTED} ("do not let LMS write grades").
 * <p>
 * Late policy (no canonical algorithm documented anywhere — smallest
 * defensible behavior, disclosed): {@code assignments.late_policy} is
 * {@code {"allowLate": true|false}}; null/absent/unparseable defaults to
 * {@code true} (late accepted as {@code LATE}), matching
 * docs/state-machines.md #16's default {@code DRAFT → LATE} path rather
 * than silently rejecting work when no policy was ever configured.
 * <p>
 * {@code @NamedInterface} added Day 13: {@link #listForStudent} gives
 * {@code risk.application.RiskFeatureService} every submission a student
 * has ever made, across all their assignments, for the
 * assignment-completion feature (a lateness-rate proxy over actual
 * submissions only — see that class's javadoc for why the true
 * submitted-versus-total-assigned completion rate was not used: it would
 * require plumbing a cross-section assignment-roster query this method
 * deliberately avoids inventing) — deliberately no authorization of its
 * own, same narrow-port precedent as every other Day 13 cross-module read.
 */
@org.springframework.modulith.NamedInterface
@Service
public class AssignmentSubmissionService {

    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final AssignmentSubmissionRepository assignmentSubmissionRepository;
    private final AssignmentSubmissionInsertGuard assignmentSubmissionInsertGuard;
    private final AssignmentService assignmentService;
    private final LearningAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;

    public AssignmentSubmissionService(AssignmentSubmissionRepository assignmentSubmissionRepository,
                                        AssignmentSubmissionInsertGuard assignmentSubmissionInsertGuard,
                                        AssignmentService assignmentService, LearningAccessSupport accessSupport,
                                        AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                                        ObjectMapper objectMapper) {
        this.assignmentSubmissionRepository = assignmentSubmissionRepository;
        this.assignmentSubmissionInsertGuard = assignmentSubmissionInsertGuard;
        this.assignmentService = assignmentService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.objectMapper = objectMapper;
    }

    public sealed interface SubmitResult {
        record Success(UUID submissionId, AssignmentSubmissionStatus status, int attemptNo) implements SubmitResult {
        }

        record AssignmentNotFound() implements SubmitResult {
        }

        record NotEnrolled() implements SubmitResult {
        }

        record AssignmentNotAvailable() implements SubmitResult {
        }

        record DeadlinePassed() implements SubmitResult {
        }

        record DuplicateAttempt() implements SubmitResult {
        }
    }

    /**
     * Row-locks the assignment first (same {@code findByIdForUpdate} as
     * {@link AssignmentService#close}) — serializes a submission against a
     * concurrent close deterministically rather than leaving a true
     * check-then-act race (Day 10 concurrency requirement 4).
     */
    @Transactional
    public SubmitResult submit(UUID assignmentId, String contentRef) {
        Assignment assignment;
        try {
            assignment = assignmentService.findForUpdateOrThrow(assignmentId);
        } catch (IllegalArgumentException e) {
            return new SubmitResult.AssignmentNotFound();
        }

        Optional<UUID> studentId = accessSupport.currentEnrolledStudentId(assignment.tenantId(), assignment.sectionId());
        if (studentId.isEmpty()) {
            return new SubmitResult.NotEnrolled();
        }
        if (assignment.status() != AssignmentStatus.PUBLISHED) {
            return new SubmitResult.AssignmentNotAvailable();
        }
        // Day 10 never implements resubmission (docs/state-machines.md #16's only path to a second attempt is
        // GRADED -> RESUBMISSION_REQUESTED -> a new attempt_no, explicitly Day 11 scope) — a student who has ALREADY
        // submitted any attempt gets rejected outright, not silently issued attempt 2. This also gives the
        // concurrent-duplicate-submit test real teeth: both racing calls see "no existing submission" and both try
        // attemptNo=1, so the DB unique constraint (not this count check) decides the loser.
        if (assignmentSubmissionRepository.countByAssignmentIdAndStudentId(assignmentId, studentId.get()) > 0) {
            return new SubmitResult.DuplicateAttempt();
        }

        Instant now = Instant.now();
        boolean isLate = assignment.dueAt() != null && now.isAfter(assignment.dueAt());
        if (isLate && !lateAllowed(assignment.latePolicy())) {
            return new SubmitResult.DeadlinePassed();
        }
        AssignmentSubmissionStatus status = isLate ? AssignmentSubmissionStatus.LATE : AssignmentSubmissionStatus.SUBMITTED;

        int attemptNo = 1;
        AssignmentSubmission submission = new AssignmentSubmission(
            UUID.randomUUID(), assignmentId, studentId.get(), attemptNo, now, status, contentRef);
        try {
            assignmentSubmissionInsertGuard.insert(submission);
        } catch (DataIntegrityViolationException e) {
            return new SubmitResult.DuplicateAttempt();
        }
        auditLogWriter.write(assignment.tenantId(), authorizationContext.currentUserId(), "ASSIGNMENT_SUBMITTED",
            "learning.assignment_submissions", submission.id(),
            Map.of("assignmentId", assignmentId, "attemptNo", attemptNo, "status", status.name()));
        return new SubmitResult.Success(submission.id(), status, attemptNo);
    }

    private boolean lateAllowed(String latePolicyJson) {
        if (latePolicyJson == null || latePolicyJson.isBlank()) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(latePolicyJson);
            JsonNode allowLate = node.get("allowLate");
            return allowLate == null || allowLate.asBoolean(true);
        } catch (Exception e) {
            return true;
        }
    }

    @Transactional(readOnly = true)
    public List<AssignmentSubmission> listForAssignment(UUID assignmentId) {
        Assignment assignment = assignmentService.findById(assignmentId)
            .orElseThrow(() -> new IllegalArgumentException("assignment not found"));
        if (accessSupport.currentAssignedFacultyId(assignment.tenantId(), assignment.sectionId()).isPresent()) {
            return assignmentSubmissionRepository.findByAssignmentId(assignmentId);
        }
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(assignment.tenantId());
        if (ownStudentId.isPresent()) {
            return assignmentSubmissionRepository.findByAssignmentIdAndStudentId(assignmentId, ownStudentId.get());
        }
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("assignment_submission", UUID.randomUUID(), assignment.tenantId()));
        return assignmentSubmissionRepository.findByAssignmentId(assignmentId);
    }

    /** Day 13 cross-module port — see class javadoc. Every submission a student has made, across all assignments; no authorization of its own. */
    @Transactional(readOnly = true)
    public List<AssignmentSubmission> listForStudent(UUID studentId) {
        return assignmentSubmissionRepository.findByStudentId(studentId);
    }
}
