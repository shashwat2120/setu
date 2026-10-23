package com.setu.assessment.application;

import com.setu.assessment.domain.Assessment;
import com.setu.assessment.domain.AssessmentComponent;
import com.setu.assessment.domain.AssessmentStatus;
import com.setu.assessment.infrastructure.AssessmentComponentRepository;
import com.setu.assessment.infrastructure.AssessmentRepository;
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
 * Day 11 §Assessment: assessments.assessments CRUD and lifecycle
 * (docs/state-machines.md #17). Writes require {@code MANAGE_ASSESSMENTS}
 * (FACULTY, section-scoped via the real assignment mechanism — see {@link
 * AssessmentAccessSupport}) or {@code MANAGE_ASSESSMENTS_ALL_SECTIONS}
 * (SUPER_ADMIN/COLLEGE_ADMIN, tenant-wide, matches the authorization
 * matrix's Tenant-approve cell). Reads: assigned faculty full, enrolled
 * student from {@code OPEN} onward (a student cannot see a {@code DRAFT}/
 * {@code SCHEDULED} assessment — but must be able to see one they are
 * expected to attempt, so the matrix's read-once-published cell is read as
 * visible from OPEN onward, not literally gated to the terminal PUBLISHED
 * state — disclosed in the Day 11 report), admin via {@code VIEW_TENANT_DATA}.
 * <p>
 * {@code @NamedInterface} added Day 14: {@link #listVisibleForSection}
 * gives {@code aiexplain.application}'s {@code getUpcomingDeadlines} tool
 * every student-visible assessment for a section - no authorization of
 * its own, same narrow-port precedent as Day 13.
 */
@org.springframework.modulith.NamedInterface
@Service
public class AssessmentService {

    private static final String MANAGE_PERMISSION = "MANAGE_ASSESSMENTS";
    private static final String MANAGE_PERMISSION_ALL = "MANAGE_ASSESSMENTS_ALL_SECTIONS";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final AssessmentRepository assessmentRepository;
    private final AssessmentComponentRepository assessmentComponentRepository;
    private final CourseSectionDirectory courseSectionDirectory;
    private final AssessmentAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public AssessmentService(AssessmentRepository assessmentRepository, AssessmentComponentRepository assessmentComponentRepository,
                              CourseSectionDirectory courseSectionDirectory, AssessmentAccessSupport accessSupport,
                              AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.assessmentRepository = assessmentRepository;
        this.assessmentComponentRepository = assessmentComponentRepository;
        this.courseSectionDirectory = courseSectionDirectory;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID assessmentId) implements CreateResult {
        }

        record SectionNotFound() implements CreateResult {
        }

        record InvalidAssessmentData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID sectionId, String type, String title, BigDecimal totalMarks,
                                Integer durationMinutes, Instant startsAt, Instant endsAt) {
        Optional<UUID> tenantId = courseSectionDirectory.findTenantId(sectionId);
        if (tenantId.isEmpty()) {
            return new CreateResult.SectionNotFound();
        }
        assertManage(sectionId, tenantId.get());

        Assessment assessment;
        try {
            assessment = new Assessment(UUID.randomUUID(), tenantId.get(), sectionId, type, title, totalMarks,
                durationMinutes, startsAt, endsAt);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidAssessmentData(e.getMessage());
        }
        assessmentRepository.save(assessment);
        auditLogWriter.write(tenantId.get(), authorizationContext.currentUserId(), "ASSESSMENT_CREATED",
            "assessment.assessments", assessment.id(), Map.of("sectionId", sectionId, "title", title));
        return new CreateResult.Success(assessment.id());
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidAssessmentData(String message) implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID assessmentId, String type, String title, BigDecimal totalMarks,
                                Integer durationMinutes, Instant startsAt, Instant endsAt) {
        Optional<Assessment> maybeAssessment = assessmentRepository.findById(assessmentId);
        if (maybeAssessment.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        Assessment assessment = maybeAssessment.get();
        assertManage(assessment.sectionId(), assessment.tenantId());
        try {
            assessment.update(type, title, totalMarks, durationMinutes, startsAt, endsAt);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new UpdateResult.InvalidAssessmentData(e.getMessage());
        }
        assessmentRepository.save(assessment);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_UPDATED",
            "assessment.assessments", assessment.id(), Map.of("title", title));
        return new UpdateResult.Success();
    }

    public sealed interface TransitionResult {
        record Success() implements TransitionResult {
        }

        record NotFound() implements TransitionResult {
        }

        record InvalidTransition() implements TransitionResult {
        }

        /** Day 11 §Components: "publish-time" (actually SCHEDULED-time, per state-machines.md #17) sum/weight validation failure. */
        record ComponentWeightsInvalid(String message) implements TransitionResult {
        }

        record TooEarly() implements TransitionResult {
        }
    }

    /** DRAFT -&gt; SCHEDULED: components/weights validated here, before any student can see the assessment. */
    @Transactional
    public TransitionResult schedule(UUID assessmentId) {
        Optional<Assessment> maybeAssessment = assessmentRepository.findById(assessmentId);
        if (maybeAssessment.isEmpty()) {
            return new TransitionResult.NotFound();
        }
        Assessment assessment = maybeAssessment.get();
        assertManage(assessment.sectionId(), assessment.tenantId());
        if (!assessment.canTransitionTo(AssessmentStatus.SCHEDULED)) {
            return new TransitionResult.InvalidTransition();
        }
        List<AssessmentComponent> components = assessmentComponentRepository.findByAssessmentId(assessmentId);
        if (components.isEmpty()) {
            return new TransitionResult.ComponentWeightsInvalid("assessment has no components");
        }
        BigDecimal weightSum = components.stream().map(AssessmentComponent::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightSum.compareTo(new BigDecimal("100")) != 0) {
            return new TransitionResult.ComponentWeightsInvalid("component weights sum to " + weightSum + ", must sum to 100");
        }
        assessment.transitionTo(AssessmentStatus.SCHEDULED);
        assessmentRepository.save(assessment);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_UPDATED",
            "assessment.assessments", assessment.id(), Map.of("status", "SCHEDULED"));
        return new TransitionResult.Success();
    }

    /** SCHEDULED -&gt; OPEN: no earlier than {@code startsAt} — no scheduler infra exists, so this is an explicit faculty/admin action, rejected if called too early. */
    @Transactional
    public TransitionResult open(UUID assessmentId) {
        Optional<Assessment> maybeAssessment = assessmentRepository.findById(assessmentId);
        if (maybeAssessment.isEmpty()) {
            return new TransitionResult.NotFound();
        }
        Assessment assessment = maybeAssessment.get();
        assertManage(assessment.sectionId(), assessment.tenantId());
        if (!assessment.canTransitionTo(AssessmentStatus.OPEN)) {
            return new TransitionResult.InvalidTransition();
        }
        if (assessment.startsAt() != null && Instant.now().isBefore(assessment.startsAt())) {
            return new TransitionResult.TooEarly();
        }
        assessment.transitionTo(AssessmentStatus.OPEN);
        assessmentRepository.save(assessment);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_UPDATED",
            "assessment.assessments", assessment.id(), Map.of("status", "OPEN"));
        return new TransitionResult.Success();
    }

    /** OPEN -&gt; CLOSED — at/after {@code endsAt}, or a manual early close (both are this same explicit call; no time check, matching "at ends_at OR manual close"). */
    @Transactional
    public TransitionResult close(UUID assessmentId) {
        return transition(assessmentId, AssessmentStatus.CLOSED, "ASSESSMENT_CLOSED");
    }

    /** CLOSED -&gt; PUBLISHED. */
    @Transactional
    public TransitionResult publish(UUID assessmentId) {
        return transition(assessmentId, AssessmentStatus.PUBLISHED, "ASSESSMENT_PUBLISHED");
    }

    /** DRAFT|SCHEDULED -&gt; VOIDED. */
    @Transactional
    public TransitionResult voidAssessment(UUID assessmentId) {
        return transition(assessmentId, AssessmentStatus.VOIDED, "ASSESSMENT_UPDATED");
    }

    private TransitionResult transition(UUID assessmentId, AssessmentStatus target, String auditAction) {
        Optional<Assessment> maybeAssessment = assessmentRepository.findById(assessmentId);
        if (maybeAssessment.isEmpty()) {
            return new TransitionResult.NotFound();
        }
        Assessment assessment = maybeAssessment.get();
        assertManage(assessment.sectionId(), assessment.tenantId());
        if (!assessment.canTransitionTo(target)) {
            return new TransitionResult.InvalidTransition();
        }
        assessment.transitionTo(target);
        assessmentRepository.save(assessment);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), auditAction,
            "assessment.assessments", assessment.id(), Map.of("status", target.name()));
        return new TransitionResult.Success();
    }

    @Transactional(readOnly = true)
    public Optional<Assessment> get(UUID assessmentId) {
        return get(assessmentId, null);
    }

    /** {@code guardianForStudentId}: same meaning as on {@link #listForSection(UUID, UUID, UUID)} — a guardian's VIEW_GRADES delegation for that exact student. */
    @Transactional(readOnly = true)
    public Optional<Assessment> get(UUID assessmentId, UUID guardianForStudentId) {
        Optional<Assessment> assessment = assessmentRepository.findById(assessmentId);
        if (assessment.isEmpty()) {
            return Optional.empty();
        }
        Assessment a = assessment.get();
        boolean isFacultyOrAdmin = assertRead(a.sectionId(), a.tenantId(), guardianForStudentId);
        boolean hiddenFromStudent = (a.status() == AssessmentStatus.DRAFT || a.status() == AssessmentStatus.SCHEDULED)
            && !isFacultyOrAdmin;
        if (hiddenFromStudent) {
            return Optional.empty();
        }
        return assessment;
    }

    @Transactional(readOnly = true)
    public List<Assessment> listForSection(UUID sectionId, UUID tenantId) {
        return listForSection(sectionId, tenantId, null);
    }

    /**
     * {@code guardianForStudentId}: the frontend passes this when the caller is viewing on behalf of a specific
     * student (the guardian dashboard) rather than as faculty/admin/the student themselves — without it, a
     * guardian holding a real, active {@code VIEW_GRADES} delegation for that exact student was falling straight
     * through to {@link #assertRead}'s final {@code assertCan(VIEW_TENANT_DATA, ...)}, which guardians never
     * hold, so the call 403'd and the frontend's grades widget silently showed "No assessments yet" instead of
     * the real cause. Mirrors the same {@code delegatableForStudent} idiom {@link GradeService#assertReadAccess}
     * already uses for the grade values themselves.
     */
    @Transactional(readOnly = true)
    public List<Assessment> listForSection(UUID sectionId, UUID tenantId, UUID guardianForStudentId) {
        boolean isFacultyOrAdmin = assertRead(sectionId, tenantId, guardianForStudentId);
        List<Assessment> all = assessmentRepository.findBySectionId(sectionId);
        if (isFacultyOrAdmin) {
            return all;
        }
        return all.stream().filter(a -> a.status() != AssessmentStatus.DRAFT && a.status() != AssessmentStatus.SCHEDULED).toList();
    }

    /** The titles of these assessments, with no authorization of its own: for {@link LocalAssessmentFacts}, whose callers have authorized their own operation. */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> titlesUnchecked(java.util.Collection<UUID> assessmentIds) {
        java.util.Map<UUID, String> titles = new java.util.HashMap<>();
        assessmentRepository.findAllById(assessmentIds).forEach(a -> titles.put(a.id(), a.title()));
        return titles;
    }

    /** Day 14 cross-module port - see class javadoc. Every student-visible (not DRAFT/SCHEDULED) assessment for a section, no authorization of its own. */
    @Transactional(readOnly = true)
    public List<Assessment> listVisibleForSection(UUID sectionId) {
        return assessmentRepository.findBySectionId(sectionId).stream()
            .filter(a -> a.status() != AssessmentStatus.DRAFT && a.status() != AssessmentStatus.SCHEDULED)
            .toList();
    }

    void assertManage(UUID sectionId, UUID tenantId) {
        ResourceRef scoped = accessSupport.sectionScoped("assessment", UUID.randomUUID(), tenantId, sectionId);
        if (authorizationContext.can(MANAGE_PERMISSION, scoped).isAllowed()) {
            return;
        }
        authorizationContext.assertCan(MANAGE_PERMISSION_ALL, ResourceRef.of("assessment", UUID.randomUUID(), tenantId));
    }

    /** Non-throwing — true if the caller is assigned faculty for this section or a permitted admin, false for a student (even an enrolled one). Used by {@code QuestionService} to redact the answer-key-bearing {@code metadataJson} from student-facing question reads — exam integrity, not just a display concern. */
    boolean isManager(UUID sectionId, UUID tenantId) {
        ResourceRef scoped = accessSupport.sectionScoped("assessment", UUID.randomUUID(), tenantId, sectionId);
        return authorizationContext.can(MANAGE_PERMISSION, scoped).isAllowed()
            || authorizationContext.can(MANAGE_PERMISSION_ALL, ResourceRef.of("assessment", UUID.randomUUID(), tenantId)).isAllowed();
    }

    /** Returns true when the caller is assigned faculty or a permitted admin. */
    private boolean assertRead(UUID sectionId, UUID tenantId, UUID guardianForStudentId) {
        ResourceRef scoped = accessSupport.sectionScoped("assessment", UUID.randomUUID(), tenantId, sectionId);
        if (authorizationContext.can(MANAGE_PERMISSION, scoped).isAllowed()) {
            return true;
        }
        if (authorizationContext.can(MANAGE_PERMISSION_ALL, ResourceRef.of("assessment", UUID.randomUUID(), tenantId)).isAllowed()
            || authorizationContext.can(VIEW_PERMISSION, ResourceRef.of("assessment", UUID.randomUUID(), tenantId)).isAllowed()) {
            return true;
        }
        if (accessSupport.currentViewingStudentId(tenantId, sectionId).isPresent()) {
            return false;
        }
        if (guardianForStudentId != null) {
            UUID ownerUserId = accessSupport.ownerUserIdOf(guardianForStudentId).orElse(null);
            ResourceRef delegated = ResourceRef.of("assessment", UUID.randomUUID(), tenantId)
                .ownedBy(ownerUserId).delegatableForStudent(guardianForStudentId);
            authorizationContext.assertCan("VIEW_GRADES", delegated);
            return false;
        }
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("assessment", UUID.randomUUID(), tenantId));
        return true;
    }

    Optional<Assessment> findById(UUID assessmentId) {
        return assessmentRepository.findById(assessmentId);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID sectionId, String type, String title, java.math.BigDecimal totalMarks,
                               Integer durationMinutes, java.time.Instant startsAt, java.time.Instant endsAt) {
        return switch (create(sectionId, type, title, totalMarks, durationMinutes, startsAt, endsAt)) {
            case CreateResult.Success s -> s.assessmentId();
            default -> throw new IllegalStateException("seed assessment creation failed: " + title);
        };
    }

}
