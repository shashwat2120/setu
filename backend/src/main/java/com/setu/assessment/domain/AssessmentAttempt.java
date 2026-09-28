package com.setu.assessment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps assessment.assessment_attempts (V40). {@code (assessment_id,
 * student_id, attempt_no)} is DB-unique — the duplicate-attempt concurrency
 * guard (Day 11 concurrency requirements 1-2), same idiom as attendance
 * Day 9 / assignment submissions Day 10. Created directly at {@code
 * IN_PROGRESS} (the student "starting" an attempt IS what creates the row
 * — there is no separate not-yet-started row to create first, mirroring
 * Day 10's "skip the lingering DRAFT row" precedent). {@code score} is the
 * provisional, attempt-level auto-graded figure — distinct from {@code
 * assessment.grades.raw_marks}, the published official figure (ERD note,
 * contradiction-audit #10).
 */
@Entity
@Table(name = "assessment_attempts", schema = "assessment")
public class AssessmentAttempt {

    @Id
    private UUID id;

    @Column(name = "assessment_id", nullable = false)
    private UUID assessmentId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentAttemptStatus status;

    private BigDecimal score;

    protected AssessmentAttempt() {
    }

    public AssessmentAttempt(UUID id, UUID assessmentId, UUID studentId, int attemptNo, Instant startedAt) {
        this.id = id;
        this.assessmentId = assessmentId;
        this.studentId = studentId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.status = AssessmentAttemptStatus.IN_PROGRESS;
    }

    public UUID id() {
        return id;
    }

    public UUID assessmentId() {
        return assessmentId;
    }

    public UUID studentId() {
        return studentId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public AssessmentAttemptStatus status() {
        return status;
    }

    public BigDecimal score() {
        return score;
    }

    public boolean canTransitionTo(AssessmentAttemptStatus target) {
        return switch (status) {
            case IN_PROGRESS -> target == AssessmentAttemptStatus.SUBMITTED || target == AssessmentAttemptStatus.VOIDED;
            case SUBMITTED -> target == AssessmentAttemptStatus.EVALUATED;
            case NOT_STARTED, EVALUATED, VOIDED -> false;
        };
    }

    public void submit(Instant now) {
        if (!canTransitionTo(AssessmentAttemptStatus.SUBMITTED)) {
            throw new IllegalStateException("cannot submit attempt from " + status);
        }
        this.status = AssessmentAttemptStatus.SUBMITTED;
        this.submittedAt = now;
    }

    public void markEvaluated(BigDecimal score) {
        if (!canTransitionTo(AssessmentAttemptStatus.EVALUATED)) {
            throw new IllegalStateException("cannot evaluate attempt from " + status);
        }
        this.status = AssessmentAttemptStatus.EVALUATED;
        this.score = score;
    }

    public void voidAttempt() {
        if (!canTransitionTo(AssessmentAttemptStatus.VOIDED)) {
            throw new IllegalStateException("cannot void attempt from " + status);
        }
        this.status = AssessmentAttemptStatus.VOIDED;
    }
}
