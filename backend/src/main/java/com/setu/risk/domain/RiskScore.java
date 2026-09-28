package com.setu.risk.domain;

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
 * Maps risk.risk_scores (V47) — state-machines.md #21 (Risk Score): append-
 * only, no lifecycle of its own. A new calculation is always a new row,
 * never an update; "current" is the row with the latest {@code
 * calculatedAt} for a student (see {@code RiskScoreRepository}'s query).
 * <p>
 * {@code featureSnapshot} stores the actual raw feature values used (not
 * just their hash) — see contradiction-audit.md #11: a hash alone cannot
 * reconstruct anything; it can only verify a value you already have.
 * Storing the JSON is what makes recalculation-and-compare actually
 * possible; {@code featureSnapshotHash} is the tamper/drift-detection
 * checksum over that same JSON. Deliberately mapped as plain TEXT (column
 * and Java type), NOT a JSON-typed column: Hibernate's JSON binding
 * re-serializes a String value through its own format mapper on the way
 * to and from the database (different whitespace than this module's own
 * {@code ObjectMapper} output), so a JSON-typed column would silently
 * break the exact-text hash contract on every round trip. A plain TEXT
 * column stores and returns the literal bytes this module wrote.
 */
@Entity
@Table(name = "risk_scores", schema = "risk")
public class RiskScore {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskBand band;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(name = "feature_snapshot_hash", nullable = false)
    private String featureSnapshotHash;

    @Column(name = "feature_snapshot", nullable = false)
    private String featureSnapshot;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    protected RiskScore() {
    }

    public RiskScore(UUID id, UUID tenantId, UUID studentId, BigDecimal score, RiskBand band, String ruleVersion,
                      String featureSnapshotHash, String featureSnapshot, Instant calculatedAt) {
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("score must be >= 0");
        }
        if (ruleVersion == null || ruleVersion.isBlank()) {
            throw new IllegalArgumentException("ruleVersion must not be blank");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.score = score;
        this.band = band;
        this.ruleVersion = ruleVersion;
        this.featureSnapshotHash = featureSnapshotHash;
        this.featureSnapshot = featureSnapshot;
        this.calculatedAt = calculatedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID studentId() {
        return studentId;
    }

    public BigDecimal score() {
        return score;
    }

    public RiskBand band() {
        return band;
    }

    public String ruleVersion() {
        return ruleVersion;
    }

    public String featureSnapshotHash() {
        return featureSnapshotHash;
    }

    public String featureSnapshot() {
        return featureSnapshot;
    }

    public Instant calculatedAt() {
        return calculatedAt;
    }
}
