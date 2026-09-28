package com.setu.risk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps risk.recommendations (V48) — state-machines.md #22 exactly:
 * PROPOSED (created by the rule engine or {@code ai-explain}, recorded in
 * {@code generatedBy}) -&gt; ACCEPTED or DISMISSED, both human-only
 * transitions ("an AI-generated recommendation cannot self-transition to
 * ACCEPTED" — canonical spec's AI-authority ban applies here too). Both
 * terminal states.
 */
@Entity
@Table(name = "recommendations", schema = "risk")
public class Recommendation {

    @Id
    private UUID id;

    @Column(name = "risk_score_id", nullable = false)
    private UUID riskScoreId;

    @Column(name = "action_code", nullable = false)
    private String actionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "generated_by", nullable = false)
    private GeneratedBy generatedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecommendationStatus status;

    protected Recommendation() {
    }

    public Recommendation(UUID id, UUID riskScoreId, String actionCode, GeneratedBy generatedBy) {
        if (actionCode == null || actionCode.isBlank()) {
            throw new IllegalArgumentException("actionCode must not be blank");
        }
        this.id = id;
        this.riskScoreId = riskScoreId;
        this.actionCode = actionCode;
        this.generatedBy = generatedBy;
        this.status = RecommendationStatus.PROPOSED;
    }

    public UUID id() {
        return id;
    }

    public UUID riskScoreId() {
        return riskScoreId;
    }

    public String actionCode() {
        return actionCode;
    }

    public GeneratedBy generatedBy() {
        return generatedBy;
    }

    public RecommendationStatus status() {
        return status;
    }

    public boolean canTransitionTo(RecommendationStatus target) {
        return status == RecommendationStatus.PROPOSED
            && (target == RecommendationStatus.ACCEPTED || target == RecommendationStatus.DISMISSED);
    }
}
