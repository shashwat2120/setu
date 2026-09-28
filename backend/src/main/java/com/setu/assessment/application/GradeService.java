package com.setu.assessment.application;

import com.setu.assessment.domain.Assessment;
import com.setu.assessment.domain.AssessmentComponent;
import com.setu.assessment.domain.Grade;
import com.setu.assessment.domain.GradePublishedEvent;
import com.setu.assessment.infrastructure.GradeRepository;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 11 §Grades/§Publication/§Correction: assessment.grades — the
 * authoritative, versioned mark for one (enrollment, component) pair.
 * Append-only (docs/state-machines.md #19); see {@link Grade}'s javadoc.
 * <p>
 * Concurrency per transaction-concurrency-matrix.md scenario 6: locks the
 * owning {@code assessment_components} row first ({@code
 * AssessmentComponentService#findByIdForUpdate}), not the mutable {@code
 * grades.is_current} row — a first design attempt locked the current grade
 * row directly and failed under real concurrency testing: PostgreSQL's
 * {@code SELECT ... FOR UPDATE} only re-evaluates the SPECIFIC row(s) it
 * was blocked on when the lock releases, never a fresh scan, so a waiter
 * blocked on the old current row would unblock having missed the brand-new
 * row the winner just inserted (a phantom-row gap), miscomputing the same
 * "next version" the winner already used. The component row is stable
 * (never inserted/deleted by this operation), so locking it first fully
 * serializes every publish/correct call for that component — by the time
 * a waiter's lock is granted, an ordinary (unlocked) read of the current
 * grade always sees the latest committed state, first publication and
 * correction alike, with no special-cased race path needed for either.
 * <p>
 * Writes {@code MANAGE_SECTION_GRADES} (the pre-existing Day 4 permission,
 * already FACULTY-role-granted, reused rather than duplicated — section-
 * scoped via {@link AssessmentAccessSupport#sectionScoped}, never a bypass)
 * or {@code MANAGE_SECTION_GRADES_ALL_SECTIONS} (SUPER_ADMIN/COLLEGE_ADMIN,
 * matches the matrix's tenant-wide correct-with-audit cell). Reads: the owning
 * student via {@code VIEW_GRADES} (pre-existing, already STUDENT-role-
 * granted AND guardian-delegatable since Day 4 — this is the first real
 * caller of the guardian path for grades), assigned faculty, or admin.
 * <p>
 * Never writes {@code assessment.grades} FROM {@code learning} — LMS
 * assignment submissions have no path into this service at all (Day 11
 * explicit boundary that LMS may create submissions but must never
 * calculate or write authoritative grades). Never writes {@code
 * record.student_academic_records} directly either — publishes {@link
 * GradePublishedEvent} to the outbox only; {@code record} (unimplemented,
 * empty module skeleton, correctly out of Day 11 scope) is the documented
 * future consumer, per the ERD's H3 decision.
 * <p>
 * {@code @NamedInterface} added Day 13: {@link #findCurrentForEnrollment}
 * gives {@code risk.application.RiskFeatureService} a student's current
 * grades across every component of one enrollment in a single call, for
 * the assessment-performance and recent-failures features — deliberately
 * no authorization of its own (same narrow-port precedent as {@code
 * DepartmentService#findTenantId}), since the caller's own entry point is
 * what's gated.
 */
@org.springframework.modulith.NamedInterface
@Service
public class GradeService {

    private static final String MANAGE_PERMISSION = "MANAGE_SECTION_GRADES";
    private static final String MANAGE_PERMISSION_ALL = "MANAGE_SECTION_GRADES_ALL_SECTIONS";
    private static final String VIEW_PERMISSION_STUDENT = "VIEW_GRADES";
    private static final String VIEW_PERMISSION_ADMIN = "VIEW_TENANT_DATA";

    private final GradeRepository gradeRepository;
    private final AssessmentService assessmentService;
    private final AssessmentComponentService assessmentComponentService;
    private final EnrollmentService enrollmentService;
    private final AssessmentAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public GradeService(GradeRepository gradeRepository, AssessmentService assessmentService,
                         AssessmentComponentService assessmentComponentService, EnrollmentService enrollmentService,
                         AssessmentAccessSupport accessSupport, AuthorizationContext authorizationContext,
                         AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter) {
        this.gradeRepository = gradeRepository;
        this.assessmentService = assessmentService;
        this.assessmentComponentService = assessmentComponentService;
        this.enrollmentService = enrollmentService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    public sealed interface PublishResult {
        record Success(UUID gradeId, int version, boolean isCorrection) implements PublishResult {
        }

        record AssessmentNotFound() implements PublishResult {
        }

        record ComponentNotFound() implements PublishResult {
        }

        record ComponentNotInAssessment() implements PublishResult {
        }

        record NotEnrolled() implements PublishResult {
        }

        record InvalidGradeData(String message) implements PublishResult {
        }
    }

    @Transactional
    public PublishResult publishOrCorrect(UUID assessmentId, UUID componentId, UUID studentId, BigDecimal rawMarks,
                                           String gradeCode, BigDecimal gradePoint) {
        Optional<Assessment> maybeAssessment = assessmentService.findById(assessmentId);
        if (maybeAssessment.isEmpty()) {
            return new PublishResult.AssessmentNotFound();
        }
        Assessment assessment = maybeAssessment.get();
        assessmentService.assertManage(assessment.sectionId(), assessment.tenantId());

        // Locks the component FIRST — see class javadoc. Every concurrent publish/correct call for this component
        // now fully serializes here, before either reads the current grade.
        Optional<AssessmentComponent> maybeComponent = assessmentComponentService.findByIdForUpdate(componentId);
        if (maybeComponent.isEmpty()) {
            return new PublishResult.ComponentNotFound();
        }
        AssessmentComponent component = maybeComponent.get();
        if (!component.assessmentId().equals(assessmentId)) {
            return new PublishResult.ComponentNotInAssessment();
        }

        Optional<UUID> enrollmentId = accessSupport.activeEnrollmentId(assessment.sectionId(), studentId);
        if (enrollmentId.isEmpty()) {
            return new PublishResult.NotEnrolled();
        }

        UUID actor = authorizationContext.currentUserId();
        Instant now = Instant.now();

        // Plain (unlocked) read — safe now: the component lock already excludes every other concurrent caller for
        // this component, so this always sees the latest committed state, never a stale or phantom-missed row.
        Optional<Grade> current = gradeRepository.findByEnrollmentIdAndComponentIdAndIsCurrentTrue(enrollmentId.get(), componentId);
        if (current.isPresent()) {
            Grade old = current.get();
            old.supersede();
            gradeRepository.save(old);
            Grade next;
            try {
                next = new Grade(UUID.randomUUID(), assessment.tenantId(), enrollmentId.get(), componentId, rawMarks,
                    gradeCode, gradePoint, old.version() + 1, old.id());
            } catch (IllegalArgumentException e) {
                return new PublishResult.InvalidGradeData(e.getMessage());
            }
            next.publish(actor, now);
            gradeRepository.save(next);
            writeOutboxAndAudit(assessment.tenantId(), next, "GRADE_CORRECTED");
            return new PublishResult.Success(next.id(), next.version(), true);
        }

        Grade first;
        try {
            first = new Grade(UUID.randomUUID(), assessment.tenantId(), enrollmentId.get(), componentId, rawMarks,
                gradeCode, gradePoint, 1, null);
        } catch (IllegalArgumentException e) {
            return new PublishResult.InvalidGradeData(e.getMessage());
        }
        first.publish(actor, now);
        gradeRepository.saveAndFlush(first);
        auditLogWriter.write(assessment.tenantId(), actor, "GRADE_CREATED", "assessment.grades", first.id(),
            Map.of("enrollmentId", enrollmentId.get(), "componentId", componentId));
        writeOutboxAndAudit(assessment.tenantId(), first, "GRADE_PUBLISHED");
        return new PublishResult.Success(first.id(), 1, false);
    }

    private void writeOutboxAndAudit(UUID tenantId, Grade grade, String auditAction) {
        UUID correlationId = UUID.randomUUID();
        var event = new GradePublishedEvent(UUID.randomUUID(), Instant.now(), tenantId, grade.id(), correlationId, null,
            grade.id(), grade.enrollmentId(), grade.componentId(), grade.version(), grade.supersedesId());
        outboxEventWriter.write("grades", grade.id(), event.eventType(), event.eventVersion(), event, tenantId,
            correlationId, null);
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), auditAction, "assessment.grades",
            grade.id(), Map.of("enrollmentId", grade.enrollmentId(), "componentId", grade.componentId(), "version", grade.version()));
    }

    /** Direct-by-id lookup used only by the controller to re-fetch a row it (or the caller) just authorized/created — mirrors LearningProgressService's Day 10 {@code getById} precedent for the same reason (publishOrCorrect's own result never carries the resolved enrollmentId, since it's never trusted from the client). */
    @Transactional(readOnly = true)
    public UUID findEnrollmentIdForGrade(UUID gradeId) {
        return gradeRepository.findById(gradeId).orElseThrow().enrollmentId();
    }

    /** Own (student), assigned faculty, admin, or a guardian with an active VIEW_GRADES delegation for this exact student. */
    @Transactional(readOnly = true)
    public Optional<Grade> getCurrent(UUID enrollmentId, UUID componentId) {
        Optional<Grade> grade = gradeRepository.findByEnrollmentIdAndComponentIdAndIsCurrentTrue(enrollmentId, componentId);
        Optional<EnrollmentService.EnrollmentSummary> summary = enrollmentService.findEnrollmentSummary(enrollmentId);
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        assertReadAccess(summary.get());
        return grade;
    }

    @Transactional(readOnly = true)
    public List<Grade> history(UUID enrollmentId, UUID componentId) {
        Optional<EnrollmentService.EnrollmentSummary> summary = enrollmentService.findEnrollmentSummary(enrollmentId);
        if (summary.isEmpty()) {
            throw new IllegalArgumentException("enrollment not found");
        }
        assertReadAccess(summary.get());
        return gradeRepository.findByEnrollmentIdAndComponentIdOrderByVersionDesc(enrollmentId, componentId);
    }

    /** Day 13 cross-module port — see class javadoc. Every currently-published grade across all components of one enrollment; no authorization of its own. */
    @Transactional(readOnly = true)
    public List<Grade> findCurrentForEnrollment(UUID enrollmentId) {
        return gradeRepository.findByEnrollmentIdAndIsCurrentTrue(enrollmentId);
    }

    private void assertReadAccess(EnrollmentService.EnrollmentSummary summary) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(summary.tenantId());
        if (ownStudentId.isPresent() && ownStudentId.get().equals(summary.studentId())) {
            return;
        }
        ResourceRef scoped = accessSupport.sectionScoped("grade", UUID.randomUUID(), summary.tenantId(), summary.sectionId());
        if (authorizationContext.can(MANAGE_PERMISSION, scoped).isAllowed()) {
            return;
        }
        if (authorizationContext.can(MANAGE_PERMISSION_ALL, ResourceRef.of("grade", UUID.randomUUID(), summary.tenantId())).isAllowed()
            || authorizationContext.can(VIEW_PERMISSION_ADMIN, ResourceRef.of("grade", UUID.randomUUID(), summary.tenantId())).isAllowed()) {
            return;
        }
        UUID ownerUserId = accessSupport.ownerUserIdOf(summary.studentId()).orElse(null);
        ResourceRef delegated = ResourceRef.of("grade", UUID.randomUUID(), summary.tenantId())
            .ownedBy(ownerUserId).delegatableForStudent(summary.studentId());
        authorizationContext.assertCan(VIEW_PERMISSION_STUDENT, delegated);
    }
}
