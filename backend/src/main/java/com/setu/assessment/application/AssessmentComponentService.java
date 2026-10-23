package com.setu.assessment.application;

import com.setu.assessment.domain.Assessment;
import com.setu.assessment.domain.AssessmentComponent;
import com.setu.assessment.domain.AssessmentStatus;
import com.setu.assessment.infrastructure.AssessmentComponentInsertGuard;
import com.setu.assessment.infrastructure.AssessmentComponentRepository;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.authorization.AuthorizationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 11 §Components: assessment_components — only mutable while the
 * owning assessment is DRAFT (reuses {@link AssessmentService}'s
 * authorization/lookup helpers rather than re-implementing the
 * component-&gt;assessment-&gt;section walk).
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class AssessmentComponentService {

    private final AssessmentComponentRepository assessmentComponentRepository;
    private final AssessmentComponentInsertGuard assessmentComponentInsertGuard;
    private final AssessmentService assessmentService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public AssessmentComponentService(AssessmentComponentRepository assessmentComponentRepository,
                                       AssessmentComponentInsertGuard assessmentComponentInsertGuard, AssessmentService assessmentService,
                                       AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                                       org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.assessmentComponentRepository = assessmentComponentRepository;
        this.assessmentComponentInsertGuard = assessmentComponentInsertGuard;
        this.assessmentService = assessmentService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID componentId) implements CreateResult {
        }

        record AssessmentNotFound() implements CreateResult {
        }

        record NotDraft() implements CreateResult {
        }

        record InvalidComponentData(String message) implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }

        /** The section already has a component that is its final grade; a course has one final grade. */
        record FinalGradeComponentExists() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID assessmentId, String code, BigDecimal maxMarks, BigDecimal weight, Integer orderNo) {
        return create(assessmentId, code, maxMarks, weight, orderNo, false);
    }

    /**
     * @param finalGrade whether this component's published grade is the course's final grade, whose publication completes the student's
     *                   enrollment. A section has at most one such component; the check is made under a per-section lock so two components
     *                   created at once cannot both claim it.
     */
    @Transactional
    public CreateResult create(UUID assessmentId, String code, BigDecimal maxMarks, BigDecimal weight, Integer orderNo, boolean finalGrade) {
        Optional<Assessment> maybeAssessment = assessmentService.findById(assessmentId);
        if (maybeAssessment.isEmpty()) {
            return new CreateResult.AssessmentNotFound();
        }
        Assessment assessment = maybeAssessment.get();
        assessmentService.assertManage(assessment.sectionId(), assessment.tenantId());
        if (assessment.status() != AssessmentStatus.DRAFT) {
            return new CreateResult.NotDraft();
        }

        AssessmentComponent component;
        try {
            component = new AssessmentComponent(UUID.randomUUID(), assessmentId, code, maxMarks, weight, orderNo, finalGrade);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidComponentData(e.getMessage());
        }
        if (finalGrade) {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "final-grade-component:" + assessment.sectionId()).query().singleRow();
            if (assessmentComponentRepository.countFinalInSection(assessment.sectionId()) > 0) {
                return new CreateResult.FinalGradeComponentExists();
            }
        }
        try {
            assessmentComponentInsertGuard.insert(component);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_UPDATED",
            "assessment.assessment_components", component.id(), Map.of("assessmentId", assessmentId, "code", code, "finalGrade", finalGrade));
        return new CreateResult.Success(component.id());
    }

    public enum DeleteResult {SUCCESS, NOT_FOUND, NOT_DRAFT}

    @Transactional
    public DeleteResult delete(UUID componentId) {
        Optional<AssessmentComponent> maybeComponent = assessmentComponentRepository.findById(componentId);
        if (maybeComponent.isEmpty()) {
            return DeleteResult.NOT_FOUND;
        }
        AssessmentComponent component = maybeComponent.get();
        Assessment assessment = assessmentService.findById(component.assessmentId()).orElseThrow();
        assessmentService.assertManage(assessment.sectionId(), assessment.tenantId());
        if (assessment.status() != AssessmentStatus.DRAFT) {
            return DeleteResult.NOT_DRAFT;
        }
        assessmentComponentRepository.delete(component);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_UPDATED",
            "assessment.assessment_components", componentId, Map.of("assessmentId", component.assessmentId()));
        return DeleteResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public List<AssessmentComponent> listForAssessment(UUID assessmentId) {
        return listForAssessment(assessmentId, null);
    }

    /** {@code guardianForStudentId}: a guardian's VIEW_GRADES delegation for that exact student — see {@link AssessmentService#get(UUID, UUID)}. */
    @Transactional(readOnly = true)
    public List<AssessmentComponent> listForAssessment(UUID assessmentId, UUID guardianForStudentId) {
        // get() throws if the caller has no read access at all, and returns empty (no exception) if the caller can
        // read SOME assessments but this one is DRAFT/SCHEDULED and hidden from them — components inherit the same
        // visibility, so an empty Optional here means an empty component list, not an error.
        Optional<Assessment> visible = assessmentService.get(assessmentId, guardianForStudentId);
        if (visible.isEmpty()) {
            return List.of();
        }
        return assessmentComponentRepository.findByAssessmentId(assessmentId).stream()
            .sorted((a, b) -> {
                Integer oa = a.orderNo();
                Integer ob = b.orderNo();
                if (oa == null && ob == null) return 0;
                if (oa == null) return 1;
                if (ob == null) return -1;
                return oa.compareTo(ob);
            })
            .toList();
    }

    /**
     * Component id -&gt; weight, with NO authorization check of its own — a narrow cross-module read port for
     * {@code record.AcademicRecordService}, which has already authorized access to the student's academic record and
     * needs the weights only to roll component grades up into a per-course grade point. Exposes weights, never
     * assessment content, marks or questions.
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, java.math.BigDecimal> weightsUnchecked(java.util.Collection<UUID> componentIds) {
        java.util.Map<UUID, java.math.BigDecimal> weights = new java.util.HashMap<>();
        assessmentComponentRepository.findAllById(componentIds).forEach(c -> weights.put(c.id(), c.weight()));
        return weights;
    }

    /** What a component is called and how much it counts for: the title of its assessment (null if there is none), its own code and its weight. */
    public record Info(String assessmentTitle, String componentCode, java.math.BigDecimal weight) {
    }

    /** The {@link Info} of each of these components, with no authorization of its own: for {@link LocalAssessmentFacts}, whose callers have authorized their own operation. */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, Info> infoUnchecked(java.util.Collection<UUID> componentIds) {
        var components = assessmentComponentRepository.findAllById(componentIds);
        var titles = assessmentService.titlesUnchecked(components.stream().map(AssessmentComponent::assessmentId).distinct().toList());
        java.util.Map<UUID, Info> info = new java.util.HashMap<>();
        components.forEach(c -> info.put(c.id(), new Info(titles.get(c.assessmentId()), c.code(), c.weight())));
        return info;
    }

    Optional<AssessmentComponent> findById(UUID componentId) {
        return assessmentComponentRepository.findById(componentId);
    }

    /** Locking read for {@code GradeService#publishOrCorrect} — see {@code AssessmentComponentRepository.findByIdForUpdate}'s javadoc. */
    Optional<AssessmentComponent> findByIdForUpdate(UUID componentId) {
        return assessmentComponentRepository.findByIdForUpdate(componentId);
    }

    /** Public, authorization-checked single-component read for controllers — reuses the exact same visibility rule as {@link #listForAssessment}. */
    @Transactional(readOnly = true)
    public Optional<AssessmentComponent> get(UUID componentId) {
        Optional<AssessmentComponent> component = assessmentComponentRepository.findById(componentId);
        if (component.isEmpty()) {
            return Optional.empty();
        }
        Optional<Assessment> visible = assessmentService.get(component.get().assessmentId());
        return visible.isEmpty() ? Optional.empty() : component;
    }

    /** What a component is called: the title of its assessment and its own code. Plain strings, so a caller in another module needs no domain type to show a grade. */
    public record Label(String assessmentTitle, String componentCode) {
    }

    /**
     * The name of a component, under the same visibility rule as {@link #get}: empty when the caller may not read it. The title is null only if the assessment
     * itself cannot be read.
     */
    @Transactional(readOnly = true)
    public Optional<Label> labelOf(UUID componentId) {
        return get(componentId).map(c -> new Label(assessmentService.get(c.assessmentId()).map(Assessment::title).orElse(null), c.code()));
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID assessmentId, String code, java.math.BigDecimal maxMarks, java.math.BigDecimal weight, Integer orderNo) {
        return switch (create(assessmentId, code, maxMarks, weight, orderNo)) {
            case CreateResult.Success s -> s.componentId();
            default -> throw new IllegalStateException("seed assessment component creation failed: " + code);
        };
    }

    /** As {@link #createForSeed(UUID, String, java.math.BigDecimal, java.math.BigDecimal, Integer)}, for the component that is the course's final grade. */
    @Transactional
    public UUID createFinalGradeForSeed(UUID assessmentId, String code, java.math.BigDecimal maxMarks, java.math.BigDecimal weight, Integer orderNo) {
        return switch (create(assessmentId, code, maxMarks, weight, orderNo, true)) {
            case CreateResult.Success s -> s.componentId();
            default -> throw new IllegalStateException("seed final-grade component creation failed: " + code);
        };
    }

}
