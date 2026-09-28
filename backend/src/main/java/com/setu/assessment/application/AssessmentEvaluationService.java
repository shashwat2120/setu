package com.setu.assessment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.assessment.domain.Assessment;
import com.setu.assessment.domain.AssessmentAttempt;
import com.setu.assessment.domain.AssessmentAttemptStatus;
import com.setu.assessment.domain.AssessmentResponse;
import com.setu.assessment.domain.Question;
import com.setu.assessment.domain.QuestionType;
import com.setu.assessment.infrastructure.AssessmentAttemptRepository;
import com.setu.assessment.infrastructure.AssessmentResponseRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 11 §Evaluation: the ONE place correctness/points/score are ever
 * computed — never the browser, never trusted from a client-supplied
 * value. Faculty/admin-triggered (assigned-scoped, same idiom as
 * assessment management), only on a {@code SUBMITTED} attempt.
 * <p>
 * Deterministic evaluation rule per {@link QuestionType} — the exact,
 * documented algorithm (Day 11 explicit instruction):
 * <ul>
 *   <li>{@code MCQ_SINGLE}, {@code TRUE_FALSE}: the question's {@code
 *       metadataJson} must carry a text field named {@code correctOption};
 *       the response's {@code answerJson} must carry a text field named
 *       {@code selected}. Correct iff the two field values are exactly
 *       equal (case-sensitive — option identifiers, not free text).</li>
 *   <li>{@code SHORT_ANSWER}: metadata field {@code correctAnswer}, answer
 *       field {@code text}. Correct iff both values, after
 *       trimming surrounding whitespace and lower-casing, are equal — a
 *       normalized exact-string match, not fuzzy/semantic matching (no
 *       canonical algorithm exists for anything smarter, and Day 11
 *       explicitly forbids inventing an AI-based grader).</li>
 *   <li>{@code ESSAY}, or any question with a null/unparseable/incomplete
 *       metadata or answer for the above rules: no deterministic algorithm
 *       exists — {@code isCorrect}/{@code pointsAwarded} are left {@code
 *       null}, explicitly excluded from the auto-graded total, and remain
 *       for a human evaluator to judge and (in a future day) record
 *       manually. This is never silently treated as zero or as correct.</li>
 * </ul>
 * All JSON is parsed defensively — a malformed/missing field is treated as
 * "cannot auto-grade" (falls through to the ESSAY-like null/null outcome),
 * never as a thrown exception, and never deserialized into anything
 * resembling executable logic — arbitrary raw data must never become
 * trusted grading logic, Day 11 explicit instruction.
 */
@Service
public class AssessmentEvaluationService {

    private final AssessmentResponseRepository assessmentResponseRepository;
    private final AssessmentAttemptRepository assessmentAttemptRepository;
    private final AssessmentAttemptService assessmentAttemptService;
    private final QuestionService questionService;
    private final AssessmentService assessmentService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;

    public AssessmentEvaluationService(AssessmentResponseRepository assessmentResponseRepository,
                                        AssessmentAttemptRepository assessmentAttemptRepository,
                                        AssessmentAttemptService assessmentAttemptService, QuestionService questionService,
                                        AssessmentService assessmentService, AuthorizationContext authorizationContext,
                                        AuditLogWriter auditLogWriter, ObjectMapper objectMapper) {
        this.assessmentResponseRepository = assessmentResponseRepository;
        this.assessmentAttemptRepository = assessmentAttemptRepository;
        this.assessmentAttemptService = assessmentAttemptService;
        this.questionService = questionService;
        this.assessmentService = assessmentService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.objectMapper = objectMapper;
    }

    public sealed interface EvaluateResult {
        record Success(BigDecimal totalScore, int autoGradedCount, int manualPendingCount) implements EvaluateResult {
        }

        record AttemptNotFound() implements EvaluateResult {
        }

        record NotSubmitted() implements EvaluateResult {
        }
    }

    @Transactional
    public EvaluateResult evaluateAttempt(UUID attemptId) {
        Optional<AssessmentAttempt> maybeAttempt = assessmentAttemptService.findById(attemptId);
        if (maybeAttempt.isEmpty()) {
            return new EvaluateResult.AttemptNotFound();
        }
        AssessmentAttempt attempt = maybeAttempt.get();
        Assessment assessment = assessmentAttemptService.assessmentOf(attempt);
        assessmentService.assertManage(assessment.sectionId(), assessment.tenantId());
        if (attempt.status() != AssessmentAttemptStatus.SUBMITTED) {
            return new EvaluateResult.NotSubmitted();
        }

        List<AssessmentResponse> responses = assessmentResponseRepository.findByAttemptId(attemptId);
        BigDecimal total = BigDecimal.ZERO;
        int autoGraded = 0;
        int manualPending = 0;
        for (AssessmentResponse response : responses) {
            Question question = questionService.findById(response.questionId()).orElseThrow();
            EvaluationOutcome outcome = evaluate(question, response.answerJson());
            response.applyEvaluation(outcome.isCorrect(), outcome.pointsAwarded());
            assessmentResponseRepository.save(response);
            if (outcome.pointsAwarded() != null) {
                total = total.add(outcome.pointsAwarded());
                autoGraded++;
            } else {
                manualPending++;
            }
        }
        attempt.markEvaluated(total);
        assessmentAttemptRepository.save(attempt);
        auditLogWriter.write(assessment.tenantId(), authorizationContext.currentUserId(), "ASSESSMENT_EVALUATED",
            "assessment.assessment_attempts", attempt.id(),
            Map.of("attemptId", attemptId, "totalScore", total, "manualPendingCount", manualPending));
        return new EvaluateResult.Success(total, autoGraded, manualPending);
    }

    private record EvaluationOutcome(Boolean isCorrect, BigDecimal pointsAwarded) {
        static EvaluationOutcome notAutoGradable() {
            return new EvaluationOutcome(null, null);
        }
    }

    private EvaluationOutcome evaluate(Question question, String answerJson) {
        if (question.type() == null) {
            return EvaluationOutcome.notAutoGradable();
        }
        return switch (question.type()) {
            case MCQ_SINGLE, TRUE_FALSE -> evaluateExactFieldMatch(question, answerJson, "correctOption", "selected");
            case SHORT_ANSWER -> evaluateNormalizedTextMatch(question, answerJson);
            case ESSAY -> EvaluationOutcome.notAutoGradable();
        };
    }

    private EvaluationOutcome evaluateExactFieldMatch(Question question, String answerJson, String metadataField, String answerField) {
        Optional<String> expected = readTextField(question.metadataJson(), metadataField);
        Optional<String> actual = readTextField(answerJson, answerField);
        if (expected.isEmpty() || actual.isEmpty()) {
            return EvaluationOutcome.notAutoGradable();
        }
        boolean correct = expected.get().equals(actual.get());
        return new EvaluationOutcome(correct, correct ? question.points() : BigDecimal.ZERO);
    }

    private EvaluationOutcome evaluateNormalizedTextMatch(Question question, String answerJson) {
        Optional<String> expected = readTextField(question.metadataJson(), "correctAnswer");
        Optional<String> actual = readTextField(answerJson, "text");
        if (expected.isEmpty() || actual.isEmpty()) {
            return EvaluationOutcome.notAutoGradable();
        }
        boolean correct = expected.get().trim().equalsIgnoreCase(actual.get().trim());
        return new EvaluationOutcome(correct, correct ? question.points() : BigDecimal.ZERO);
    }

    private Optional<String> readTextField(String json, String field) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || !value.isTextual()) {
                return Optional.empty();
            }
            return Optional.of(value.asText());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
