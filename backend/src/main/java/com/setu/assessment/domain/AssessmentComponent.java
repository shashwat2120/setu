package com.setu.assessment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps assessment.assessment_components (V39). {@code (assessment_id,
 * code)} is DB-unique. Only mutable while the owning assessment is DRAFT
 * (enforced by the service, which loads the assessment first) — the same
 * reproducibility rule as the assessment itself.
 */
@Entity
@Table(name = "assessment_components", schema = "assessment")
public class AssessmentComponent {

    @Id
    private UUID id;

    @Column(name = "assessment_id", nullable = false)
    private UUID assessmentId;

    @Column(nullable = false)
    private String code;

    @Column(name = "max_marks", nullable = false)
    private BigDecimal maxMarks;

    @Column(nullable = false)
    private BigDecimal weight;

    @Column(name = "order_no")
    private Integer orderNo;

    protected AssessmentComponent() {
    }

    public AssessmentComponent(UUID id, UUID assessmentId, String code, BigDecimal maxMarks, BigDecimal weight, Integer orderNo) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (maxMarks == null || maxMarks.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxMarks must be > 0");
        }
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be > 0");
        }
        this.id = id;
        this.assessmentId = assessmentId;
        this.code = code;
        this.maxMarks = maxMarks;
        this.weight = weight;
        this.orderNo = orderNo;
    }

    public UUID id() {
        return id;
    }

    public UUID assessmentId() {
        return assessmentId;
    }

    public String code() {
        return code;
    }

    public BigDecimal maxMarks() {
        return maxMarks;
    }

    public BigDecimal weight() {
        return weight;
    }

    public Integer orderNo() {
        return orderNo;
    }
}
