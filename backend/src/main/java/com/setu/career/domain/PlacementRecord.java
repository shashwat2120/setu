package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps career.placement_records (V46). Append-only by design (no status/
 * version column exists in the schema, and none is added): "historical
 * placement data must not be silently overwritten" (Day 12 explicit rule)
 * is enforced simply by this module never issuing an UPDATE against this
 * table — a correction is always a brand-new row, the same append-only
 * philosophy {@link com.setu.assessment.domain.Grade Grade} uses for
 * corrections, just without that entity's extra version/supersedes
 * bookkeeping (nothing in the ERD models a placement-record correction
 * chain, so none is invented here — multiple rows for the same student are
 * simply multiple, independently true placement events, e.g. more than one
 * job offer over time).
 */
@Entity
@Table(name = "placement_records", schema = "career")
public class PlacementRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "job_id")
    private UUID jobId;

    private String employer;

    private String role;

    @Column(precision = 12, scale = 2)
    private BigDecimal ctc;

    @Column(name = "placed_at")
    private LocalDate placedAt;

    protected PlacementRecord() {
    }

    public PlacementRecord(UUID id, UUID tenantId, UUID studentId, UUID jobId, String employer, String role,
                            BigDecimal ctc, LocalDate placedAt) {
        if (employer == null || employer.isBlank()) {
            throw new IllegalArgumentException("employer must not be blank");
        }
        if (ctc != null && ctc.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("ctc must not be negative");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.jobId = jobId;
        this.employer = employer;
        this.role = role;
        this.ctc = ctc;
        this.placedAt = placedAt;
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

    public UUID jobId() {
        return jobId;
    }

    public String employer() {
        return employer;
    }

    public String role() {
        return role;
    }

    public BigDecimal ctc() {
        return ctc;
    }

    public LocalDate placedAt() {
        return placedAt;
    }
}
