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
 * Maps assessment.grades (V41; {@code uq_grades_enrollment_component_version}
 * added V65). Append-only (docs/state-machines.md #19) — never {@code
 * UPDATE} a row's {@code raw_marks}/{@code status} once PUBLISHED. A
 * correction is a brand-new row: {@code version = previous.version + 1},
 * {@code supersedesId = previous.id}, {@code isCurrent = true}; the
 * previous row's {@code isCurrent} flips to {@code false} and its status
 * moves to {@code SUPERSEDED} in the SAME transaction (see {@code
 * GradeService#publishOrCorrect}). Day 11 skips a lingering {@code DRAFT}
 * row for the same reason Day 10 skipped one for assignment submissions —
 * no "save draft" workflow was requested; every row this module creates is
 * born {@code PUBLISHED}.
 */
@Entity
@Table(name = "grades", schema = "assessment")
public class Grade {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(name = "component_id", nullable = false)
    private UUID componentId;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "raw_marks", nullable = false)
    private BigDecimal rawMarks;

    @Column(name = "grade_code")
    private String gradeCode;

    @Column(name = "grade_point")
    private BigDecimal gradePoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GradeStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int version;

    @Column(name = "supersedes_id")
    private UUID supersedesId;

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent;

    protected Grade() {
    }

    public Grade(UUID id, UUID tenantId, UUID enrollmentId, UUID componentId, BigDecimal rawMarks, String gradeCode,
                 BigDecimal gradePoint, int version, UUID supersedesId) {
        if (rawMarks == null || rawMarks.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("rawMarks must be >= 0");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.enrollmentId = enrollmentId;
        this.componentId = componentId;
        this.rawMarks = rawMarks;
        this.gradeCode = gradeCode;
        this.gradePoint = gradePoint;
        this.version = version;
        this.supersedesId = supersedesId;
        this.status = GradeStatus.DRAFT;
        this.isCurrent = true;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID enrollmentId() {
        return enrollmentId;
    }

    public UUID componentId() {
        return componentId;
    }

    public UUID publishedBy() {
        return publishedBy;
    }

    public BigDecimal rawMarks() {
        return rawMarks;
    }

    public String gradeCode() {
        return gradeCode;
    }

    public BigDecimal gradePoint() {
        return gradePoint;
    }

    public GradeStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public int version() {
        return version;
    }

    public UUID supersedesId() {
        return supersedesId;
    }

    public boolean isCurrent() {
        return isCurrent;
    }

    /** DRAFT -&gt; PUBLISHED only (state-machines.md #19). */
    public void publish(UUID publishedBy, Instant now) {
        if (status != GradeStatus.DRAFT) {
            throw new IllegalStateException("cannot publish grade from " + status);
        }
        this.status = GradeStatus.PUBLISHED;
        this.publishedBy = publishedBy;
        this.publishedAt = now;
    }

    /** PUBLISHED -&gt; SUPERSEDED only — "only a PUBLISHED (not DRAFT) grade can be superseded" (state-machines.md #19). Called on the OLD row when a correction is published. */
    public void supersede() {
        if (status != GradeStatus.PUBLISHED) {
            throw new IllegalStateException("cannot supersede grade from " + status);
        }
        this.status = GradeStatus.SUPERSEDED;
        this.isCurrent = false;
    }
}
