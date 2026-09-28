package com.setu.assessment.application;

import com.setu.assessment.domain.Assessment;
import com.setu.assessment.domain.AssessmentAttempt;
import com.setu.assessment.domain.AssessmentAttemptStatus;
import com.setu.assessment.domain.AssessmentStatus;
import com.setu.assessment.infrastructure.AssessmentAttemptInsertGuard;
import com.setu.assessment.infrastructure.AssessmentAttemptRepository;
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
 * Day 11 §Attempts: assessment_attempts — the authoritative student
 * attempt flow (docs/state-machines.md #18). The student id is always
 * resolved from the CALLER's own principal for {@link #start}/{@link
 * #submit}, never accepted as a request parameter (Day 9/10 discipline:
 * "students can't start/submit for another student" true by construction).
 * Day 11 never implements multiple attempts (no {@code
 * allows_multiple_attempts}-style flag exists anywhere in the schema) —
 * any existing row for {@code (assessment, student)}, regardless of its
 * status, blocks a new {@link #start} call; every accepted attempt is
 * {@code attempt_no = 1}. Rows are created directly at {@code IN_PROGRESS}
 * (no lingering {@code NOT_STARTED} row is ever persisted — same
 * skip-the-unused-initial-state precedent as Day 10's assignment
 * submissions).
 */
@Service
public class AssessmentAttemptService {

    private static final String MANAGE_PERMISSION = "MANAGE_ASSESSMENTS";
    private static final String MANAGE_PERMISSION_ALL = "MANAGE_ASSESSMENTS_ALL_SECTIONS";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final AssessmentAttemptRepository assessmentAttemptRepository;
    private final AssessmentAttemptInsertGuard assessmentAttemptInsertGuard;
    private final AssessmentService assessmentService;
    private final AssessmentAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public AssessmentAttemptService(AssessmentAttemptRepository assessmentAttemptRepository,
                                     AssessmentAttemptInsertGuard assessmentAttemptInsertGuard, AssessmentService assessmentService,
                                     AssessmentAccessSupport accessSupport, AuthorizationContext authorizationContext,
                                     AuditLogWriter auditLogWriter) {
        this.assessmentAttemptRepository = assessmentAttemptRepository;
        this.assessmentAttemptInsertGuard = assessmentAttemptInsertGuard;
        this.assessmentService = assessmentService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface StartResult {
        record Success(UUID attemptId) implements StartResult {
        }

        record AssessmentNotFound() implements StartResult {
        }

        record NotEnrolled() implements StartResult {
        }

        record AssessmentNotOpen() implements StartResult {
        }

        record OutsideWindow() implements StartResult {
        }

        record AlreadyAttempted() implements StartResult {
        }
    }

    @Transactional
    public StartResult start(UUID assessmentId) {
        Optional<Assessment> maybeAssessment = assessmentService.findById(assessmentId);
        if (maybeAssessment.isEmpty()) {
            return new StartResult.AssessmentNotFound();
        }
        Assessment assessment = maybeAssessment.get();

        Optional<UUID> studentId = accessSupport.currentEnrolledStudentId(assessment.tenantId(), assessment.sectionId());
        if (studentId.isEmpty()) {
            return new StartResult.NotEnrolled();
        }
        if (assessment.status() != AssessmentStatus.OPEN) {
            return new StartResult.AssessmentNotOpen();
        }
        Instant now = Instant.now();
        if (assessment.endsAt() != null && now.isAfter(assessment.endsAt())) {
            return new StartResult.OutsideWindow();
        }
        if (assessmentAttemptRepository.countByAssessmentIdAndStudentId(assessmentId, studentId.get()) > 0) {
            return new StartResult.AlreadyAttempted();
        }

        AssessmentAttempt attempt = new AssessmentAttempt(UUID.randomUUID(), assessmentId, studentId.get(), 1, now);
        try {
            assessmentAttemptInsertGuard.insert(attempt);
        } catch (DataIntegrityViolationException e) {
            return new StartResult.AlreadyAttempted();
        }
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ATTEMPT_STARTED",
            "assessment.assessment_attempts", attempt.id(), Map.of("assessmentId", assessmentId));
        return new StartResult.Success(attempt.id());
    }

    public sealed interface SubmitResult {
        record Success() implements SubmitResult {
        }

        record NotFound() implements SubmitResult {
        }

        record NotOwner() implements SubmitResult {
        }

        record InvalidTransition() implements SubmitResult {
        }
    }

    @Transactional
    public SubmitResult submit(UUID attemptId) {
        Optional<AssessmentAttempt> maybeAttempt = assessmentAttemptRepository.findById(attemptId);
        if (maybeAttempt.isEmpty()) {
            return new SubmitResult.NotFound();
        }
        AssessmentAttempt attempt = maybeAttempt.get();
        Assessment assessment = assessmentService.findById(attempt.assessmentId()).orElseThrow();
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(assessment.tenantId());
        if (ownStudentId.isEmpty() || !ownStudentId.get().equals(attempt.studentId())) {
            return new SubmitResult.NotOwner();
        }
        if (!attempt.canTransitionTo(AssessmentAttemptStatus.SUBMITTED)) {
            return new SubmitResult.InvalidTransition();
        }
        attempt.submit(Instant.now());
        assessmentAttemptRepository.save(attempt);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ATTEMPT_SUBMITTED",
            "assessment.assessment_attempts", attempt.id(), Map.of("assessmentId", attempt.assessmentId()));
        return new SubmitResult.Success();
    }

    public enum VoidResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    /** Faculty/admin-only, academic-integrity or technical-failure exception (state-machines.md #18). */
    @Transactional
    public VoidResult voidAttempt(UUID attemptId) {
        Optional<AssessmentAttempt> maybeAttempt = assessmentAttemptRepository.findById(attemptId);
        if (maybeAttempt.isEmpty()) {
            return VoidResult.NOT_FOUND;
        }
        AssessmentAttempt attempt = maybeAttempt.get();
        Assessment assessment = assessmentService.findById(attempt.assessmentId()).orElseThrow();
        assessmentService.assertManage(assessment.sectionId(), assessment.tenantId());
        if (!attempt.canTransitionTo(AssessmentAttemptStatus.VOIDED)) {
            return VoidResult.INVALID_TRANSITION;
        }
        attempt.voidAttempt();
        assessmentAttemptRepository.save(attempt);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ATTEMPT_SUBMITTED",
            "assessment.assessment_attempts", attempt.id(), Map.of("assessmentId", attempt.assessmentId(), "status", "VOIDED"));
        return VoidResult.SUCCESS;
    }

    /** Own attempt, assigned faculty (read+evaluate), or admin. */
    @Transactional(readOnly = true)
    public Optional<AssessmentAttempt> get(UUID attemptId) {
        Optional<AssessmentAttempt> attempt = assessmentAttemptRepository.findById(attemptId);
        if (attempt.isEmpty()) {
            return Optional.empty();
        }
        AssessmentAttempt a = attempt.get();
        Assessment assessment = assessmentService.findById(a.assessmentId()).orElseThrow();
        assertOwnOrManage(a, assessment);
        return attempt;
    }

    @Transactional(readOnly = true)
    /**
     * Day 16 §student: real gap found live — a student could start their own attempt ({@link #start}, already
     * self-scoped) but not list it back, since this previously required the same tenant-admin/faculty-manage
     * permission as {@link #listForAssessment}'s original unconditional check. A caller holding a student
     * profile in this tenant gets their OWN attempts only (filtered, never another student's, and empty rather
     * than denied for an assessment they have no attempt on) — everyone else still needs manage authorization,
     * matching the {@link #assertOwnOrManage} idiom already used by {@link #submit}/{@link #voidAttempt}.
     */
    public List<AssessmentAttempt> listForAssessment(UUID assessmentId) {
        Assessment assessment = assessmentService.findById(assessmentId)
            .orElseThrow(() -> new IllegalArgumentException("assessment not found"));
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(assessment.tenantId());
        if (ownStudentId.isPresent()) {
            return assessmentAttemptRepository.findByAssessmentId(assessmentId).stream()
                .filter(a -> a.studentId().equals(ownStudentId.get())).toList();
        }
        assessmentService.assertManage(assessment.sectionId(), assessment.tenantId());
        return assessmentAttemptRepository.findByAssessmentId(assessmentId);
    }

    void assertOwnOrManage(AssessmentAttempt attempt, Assessment assessment) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(assessment.tenantId());
        if (ownStudentId.isPresent() && ownStudentId.get().equals(attempt.studentId())) {
            return;
        }
        ResourceRef scoped = accessSupport.sectionScoped("assessment_attempt", attempt.id(), assessment.tenantId(), assessment.sectionId());
        if (authorizationContext.can(MANAGE_PERMISSION, scoped).isAllowed()) {
            return;
        }
        if (authorizationContext.can(MANAGE_PERMISSION_ALL, ResourceRef.of("assessment_attempt", attempt.id(), assessment.tenantId())).isAllowed()) {
            return;
        }
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("assessment_attempt", attempt.id(), assessment.tenantId()));
    }

    Optional<AssessmentAttempt> findById(UUID attemptId) {
        return assessmentAttemptRepository.findById(attemptId);
    }

    Assessment assessmentOf(AssessmentAttempt attempt) {
        return assessmentService.findById(attempt.assessmentId()).orElseThrow();
    }
}
