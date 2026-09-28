package com.setu.risk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps risk.risk_factors (V47) — one row per contributing factor of a
 * {@link RiskScore}, ranked by {@code contribution}. This is what {@code
 * ai-explain} (Day 14) is handed, never the raw feature vector, so the AI
 * never has to (and can't) derive its own weighting — see the ERD's own
 * note. {@code evidenceRefs} is a JSON array of {@code {table, id}}
 * references a reviewer can actually look up — see {@link
 * com.setu.risk.application.RiskFeatureService}'s javadoc for exactly what
 * each factor code references and why.
 */
@Entity
@Table(name = "risk_factors", schema = "risk")
public class RiskFactor {

    @Id
    private UUID id;

    @Column(name = "risk_score_id", nullable = false)
    private UUID riskScoreId;

    @Column(name = "factor_code", nullable = false)
    private String factorCode;

    @Column(precision = 5, scale = 3)
    private BigDecimal contribution;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", nullable = false)
    private String evidenceRefs;

    protected RiskFactor() {
    }

    public RiskFactor(UUID id, UUID riskScoreId, String factorCode, BigDecimal contribution, String evidenceRefs) {
        if (factorCode == null || factorCode.isBlank()) {
            throw new IllegalArgumentException("factorCode must not be blank");
        }
        this.id = id;
        this.riskScoreId = riskScoreId;
        this.factorCode = factorCode;
        this.contribution = contribution;
        this.evidenceRefs = evidenceRefs == null ? "[]" : evidenceRefs;
    }

    public UUID id() {
        return id;
    }

    public UUID riskScoreId() {
        return riskScoreId;
    }

    public String factorCode() {
        return factorCode;
    }

    public BigDecimal contribution() {
        return contribution;
    }

    public String evidenceRefs() {
        return evidenceRefs;
    }
}
