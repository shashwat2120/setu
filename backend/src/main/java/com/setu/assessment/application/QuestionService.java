package com.setu.assessment.application;

import com.setu.assessment.domain.Assessment;
import com.setu.assessment.domain.AssessmentComponent;
import com.setu.assessment.domain.AssessmentStatus;
import com.setu.assessment.domain.Question;
import com.setu.assessment.domain.QuestionType;
import com.setu.assessment.infrastructure.QuestionRepository;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.authorization.AuthorizationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 11 §Questions: questions — only mutable while the owning assessment
 * is DRAFT. {@code skillId} is stored but never validated cross-module
 * (career is out of Day 11 scope). {@code metadataJson} is stored and read
 * back as opaque text only — {@link com.setu.assessment.application.
 * AssessmentEvaluationService} is the ONE place it is ever parsed, and only
 * for the specific, whitelisted key(s) each {@link QuestionType}'s
 * deterministic rule needs (never deserialized into executable logic).
 */
@Service
public class QuestionService {

    private final QuestionRepository questionRepository;
    private final AssessmentComponentService assessmentComponentService;
    private final AssessmentService assessmentService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public QuestionService(QuestionRepository questionRepository, AssessmentComponentService assessmentComponentService,
                            AssessmentService assessmentService, AuthorizationContext authorizationContext,
                            AuditLogWriter auditLogWriter) {
        this.questionRepository = questionRepository;
        this.assessmentComponentService = assessmentComponentService;
        this.assessmentService = assessmentService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID questionId) implements CreateResult {
        }

        record ComponentNotFound() implements CreateResult {
        }

        record NotDraft() implements CreateResult {
        }

        record InvalidQuestionData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID componentId, QuestionType type, String prompt, BigDecimal points, String difficulty,
                                UUID skillId, String metadataJson) {
        Optional<AssessmentComponent> maybeComponent = assessmentComponentService.findById(componentId);
        if (maybeComponent.isEmpty()) {
            return new CreateResult.ComponentNotFound();
        }
        AssessmentComponent component = maybeComponent.get();
        Assessment assessment = assessmentService.findById(component.assessmentId()).orElseThrow();
        assessmentService.assertManage(assessment.sectionId(), assessment.tenantId());
        if (assessment.status() != AssessmentStatus.DRAFT) {
            return new CreateResult.NotDraft();
        }

        Question question;
        try {
            question = new Question(UUID.randomUUID(), componentId, type, prompt, points, difficulty, skillId, metadataJson);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidQuestionData(e.getMessage());
        }
        questionRepository.save(question);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_UPDATED",
            "assessment.questions", question.id(), Map.of("componentId", componentId));
        return new CreateResult.Success(question.id());
    }

    public enum DeleteResult {SUCCESS, NOT_FOUND, NOT_DRAFT}

    @Transactional
    public DeleteResult delete(UUID questionId) {
        Optional<Question> maybeQuestion = questionRepository.findById(questionId);
        if (maybeQuestion.isEmpty()) {
            return DeleteResult.NOT_FOUND;
        }
        Question question = maybeQuestion.get();
        AssessmentComponent component = assessmentComponentService.findById(question.componentId()).orElseThrow();
        Assessment assessment = assessmentService.findById(component.assessmentId()).orElseThrow();
        assessmentService.assertManage(assessment.sectionId(), assessment.tenantId());
        if (assessment.status() != AssessmentStatus.DRAFT) {
            return DeleteResult.NOT_DRAFT;
        }
        questionRepository.delete(question);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_UPDATED",
            "assessment.questions", questionId, Map.of("componentId", question.componentId()));
        return DeleteResult.SUCCESS;
    }

    /**
     * {@code metadataJson} (the answer key for auto-gradable types) is
     * redacted to {@code null} for any non-manager caller — an enrolled
     * student can see a question's prompt/points/type while attempting the
     * assessment, never the grading key, exam integrity being the point,
     * not just data minimization.
     */
    @Transactional(readOnly = true)
    public List<Question> listForComponent(UUID componentId) {
        AssessmentComponent component = assessmentComponentService.findById(componentId)
            .orElseThrow(() -> new IllegalArgumentException("component not found"));
        Optional<Assessment> visible = assessmentService.get(component.assessmentId());
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Question> questions = questionRepository.findByComponentId(componentId);
        boolean isManager = assessmentService.isManager(visible.get().sectionId(), visible.get().tenantId());
        if (isManager) {
            return questions;
        }
        return questions.stream()
            .map(q -> new Question(q.id(), q.componentId(), q.type(), q.prompt(), q.points(), q.difficulty(), q.skillId(), null))
            .toList();
    }

    Optional<Question> findById(UUID questionId) {
        return questionRepository.findById(questionId);
    }

    AssessmentComponent componentOf(Question question) {
        return assessmentComponentService.findById(question.componentId()).orElseThrow();
    }

    /** Public, authorization-checked single-question read for controllers — same visibility/redaction rule as {@link #listForComponent}. */
    @Transactional(readOnly = true)
    public Optional<Question> get(UUID questionId) {
        Optional<Question> question = questionRepository.findById(questionId);
        if (question.isEmpty()) {
            return Optional.empty();
        }
        return listForComponent(question.get().componentId()).stream()
            .filter(q -> q.id().equals(questionId))
            .findFirst();
    }
}
