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

    public AssessmentComponentService(AssessmentComponentRepository assessmentComponentRepository,
                                       AssessmentComponentInsertGuard assessmentComponentInsertGuard, AssessmentService assessmentService,
                                       AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
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
    }

    @Transactional
    public CreateResult create(UUID assessmentId, String code, BigDecimal maxMarks, BigDecimal weight, Integer orderNo) {
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
            component = new AssessmentComponent(UUID.randomUUID(), assessmentId, code, maxMarks, weight, orderNo);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidComponentData(e.getMessage());
        }
        try {
            assessmentComponentInsertGuard.insert(component);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_UPDATED",
            "assessment.assessment_components", component.id(), Map.of("assessmentId", assessmentId, "code", code));
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

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID assessmentId, String code, java.math.BigDecimal maxMarks, java.math.BigDecimal weight, Integer orderNo) {
        return switch (create(assessmentId, code, maxMarks, weight, orderNo)) {
            case CreateResult.Success s -> s.componentId();
            default -> throw new IllegalStateException("seed assessment component creation failed: " + code);
        };
    }

}
