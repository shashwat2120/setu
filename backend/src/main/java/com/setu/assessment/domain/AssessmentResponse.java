package com.setu.assessment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps assessment.assessment_responses (V40). {@code (attempt_id,
 * question_id)} is DB-unique — the concurrent-same-question-response guard
 * (Day 11 concurrency requirement 3). {@code isCorrect}/{@code
 * pointsAwarded} are ALWAYS server-computed by {@code
 * AssessmentEvaluationService} — a response is created with both null and
 * only the evaluator ever sets them (never trusting a client-supplied
 * correctness/points value, Day 11 explicit instruction).
 */
@Entity
@Table(name = "assessment_responses", schema = "assessment")
public class AssessmentResponse {

    @Id
    private UUID id;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_json", columnDefinition = "jsonb")
    private String answerJson;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column(name = "points_awarded")
    private BigDecimal pointsAwarded;

    @Version
    private int version;

    protected AssessmentResponse() {
    }

    public AssessmentResponse(UUID id, UUID attemptId, UUID questionId, String answerJson) {
        this.id = id;
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.answerJson = answerJson;
    }

    public UUID id() {
        return id;
    }

    public UUID attemptId() {
        return attemptId;
    }

    public UUID questionId() {
        return questionId;
    }

    public String answerJson() {
        return answerJson;
    }

    public Boolean isCorrect() {
        return isCorrect;
    }

    public BigDecimal pointsAwarded() {
        return pointsAwarded;
    }

    public int version() {
        return version;
    }

    /** Server-side only — see class javadoc. */
    public void applyEvaluation(Boolean isCorrect, BigDecimal pointsAwarded) {
        this.isCorrect = isCorrect;
        this.pointsAwarded = pointsAwarded;
    }
}
