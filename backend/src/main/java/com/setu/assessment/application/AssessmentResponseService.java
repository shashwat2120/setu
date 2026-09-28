package com.setu.assessment.application;

import com.setu.assessment.domain.AssessmentAttempt;
import com.setu.assessment.domain.AssessmentAttemptStatus;
import com.setu.assessment.domain.AssessmentComponent;
import com.setu.assessment.domain.AssessmentResponse;
import com.setu.assessment.domain.Question;
import com.setu.assessment.infrastructure.AssessmentResponseInsertGuard;
import com.setu.assessment.infrastructure.AssessmentResponseRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 11 §Responses: assessment_responses — the student's own answer
 * submission for one question within an attempt. {@code
 * isCorrect}/{@code pointsAwarded} are never accepted here (no parameter
 * exists for them) — only {@link AssessmentEvaluationService} ever writes
 * them, server-side, after submission. Immutable once the attempt leaves
 * {@code IN_PROGRESS} (Day 11 "prevent responses after submission where
 * state machine requires immutability").
 */
@Service
public class AssessmentResponseService {

    private final AssessmentResponseRepository assessmentResponseRepository;
    private final AssessmentResponseInsertGuard assessmentResponseInsertGuard;
    private final AssessmentAttemptService assessmentAttemptService;
    private final QuestionService questionService;
    private final AssessmentAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public AssessmentResponseService(AssessmentResponseRepository assessmentResponseRepository,
                                      AssessmentResponseInsertGuard assessmentResponseInsertGuard,
                                      AssessmentAttemptService assessmentAttemptService, QuestionService questionService,
                                      AssessmentAccessSupport accessSupport, AuthorizationContext authorizationContext,
                                      AuditLogWriter auditLogWriter) {
        this.assessmentResponseRepository = assessmentResponseRepository;
        this.assessmentResponseInsertGuard = assessmentResponseInsertGuard;
        this.assessmentAttemptService = assessmentAttemptService;
        this.questionService = questionService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface RecordResult {
        record Success(UUID responseId) implements RecordResult {
        }

        record AttemptNotFound() implements RecordResult {
        }

        record NotOwner() implements RecordResult {
        }

        record AttemptNotInProgress() implements RecordResult {
        }

        record QuestionNotFound() implements RecordResult {
        }

        record QuestionNotInAssessment() implements RecordResult {
        }

        record VersionConflict() implements RecordResult {
        }
    }

    @Transactional
    public RecordResult recordResponse(UUID attemptId, UUID questionId, String answerJson) {
        Optional<AssessmentAttempt> maybeAttempt = assessmentAttemptService.findById(attemptId);
        if (maybeAttempt.isEmpty()) {
            return new RecordResult.AttemptNotFound();
        }
        AssessmentAttempt attempt = maybeAttempt.get();

        Optional<UUID> ownStudentId = accessSupport.currentStudentId(resolveTenantId(attempt));
        if (ownStudentId.isEmpty() || !ownStudentId.get().equals(attempt.studentId())) {
            return new RecordResult.NotOwner();
        }
        if (attempt.status() != AssessmentAttemptStatus.IN_PROGRESS) {
            return new RecordResult.AttemptNotInProgress();
        }

        Optional<Question> maybeQuestion = questionService.findById(questionId);
        if (maybeQuestion.isEmpty()) {
            return new RecordResult.QuestionNotFound();
        }
        Question question = maybeQuestion.get();
        AssessmentComponent component = questionService.componentOf(question);
        if (!component.assessmentId().equals(attempt.assessmentId())) {
            return new RecordResult.QuestionNotInAssessment();
        }

        Optional<AssessmentResponse> existing = assessmentResponseRepository.findByAttemptIdAndQuestionId(attemptId, questionId);
        if (existing.isEmpty()) {
            AssessmentResponse response = new AssessmentResponse(UUID.randomUUID(), attemptId, questionId, answerJson);
            try {
                assessmentResponseInsertGuard.insert(response);
            } catch (DataIntegrityViolationException e) {
                existing = assessmentResponseRepository.findByAttemptIdAndQuestionId(attemptId, questionId);
                return applyUpdate(existing.orElseThrow(), answerJson, attempt);
            }
            auditLogWriter.write(resolveTenantId(attempt), authorizationContext.currentUserId(), "RESPONSE_RECORDED",
                "assessment.assessment_responses", response.id(), Map.of("attemptId", attemptId, "questionId", questionId));
            return new RecordResult.Success(response.id());
        }
        return applyUpdate(existing.get(), answerJson, attempt);
    }

    private RecordResult applyUpdate(AssessmentResponse current, String answerJson, AssessmentAttempt attempt) {
        boolean updated = assessmentResponseInsertGuard.updateAnswerIfVersionMatches(current.id(), answerJson, current.version());
        if (!updated) {
            return new RecordResult.VersionConflict();
        }
        auditLogWriter.write(resolveTenantId(attempt), authorizationContext.currentUserId(), "RESPONSE_RECORDED",
            "assessment.assessment_responses", current.id(), Map.of("attemptId", current.attemptId(), "questionId", current.questionId()));
        return new RecordResult.Success(current.id());
    }

    @Transactional(readOnly = true)
    public List<AssessmentResponse> listForAttempt(UUID attemptId) {
        assessmentAttemptService.get(attemptId)
            .orElseThrow(() -> new IllegalArgumentException("attempt not found")); // reuses own-or-manage authorization; throws if unauthorized
        return assessmentResponseRepository.findByAttemptId(attemptId);
    }

    private UUID resolveTenantId(AssessmentAttempt attempt) {
        return assessmentAttemptService.assessmentOf(attempt).tenantId();
    }
}
