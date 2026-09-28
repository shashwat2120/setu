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
 * Maps assessment.assessments (V39). docs/state-machines.md #17 exactly:
 * {@code DRAFT -> SCHEDULED} (components/weights sum-validated here, before
 * students can see it — {@link com.setu.assessment.application.
 * AssessmentService#schedule}), {@code SCHEDULED -> OPEN} (no earlier than
 * {@code startsAt}), {@code OPEN -> CLOSED} (at/after {@code endsAt}, or a
 * manual early close), {@code CLOSED -> PUBLISHED} (faculty releases
 * results), {@code DRAFT|SCHEDULED -> VOIDED}. {@code totalMarks}/{@code
 * startsAt}/{@code endsAt} are immutable once {@code SCHEDULED} — a
 * completed attempt must always be evaluated against the exact definition
 * it was taken under (ERD note); {@link #update} enforces DRAFT-only.
 */
@Entity
@Table(name = "assessments", schema = "assessment")
public class Assessment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    private String type;

    @Column(nullable = false)
    private String title;

    @Column(name = "total_marks")
    private BigDecimal totalMarks;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentStatus status;

    protected Assessment() {
    }

    public Assessment(UUID id, UUID tenantId, UUID sectionId, String type, String title, BigDecimal totalMarks,
                       Integer durationMinutes, Instant startsAt, Instant endsAt) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (totalMarks != null && totalMarks.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("totalMarks must be > 0");
        }
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("startsAt must be before endsAt");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.sectionId = sectionId;
        this.type = type;
        this.title = title;
        this.totalMarks = totalMarks;
        this.durationMinutes = durationMinutes;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = AssessmentStatus.DRAFT;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID sectionId() {
        return sectionId;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    public BigDecimal totalMarks() {
        return totalMarks;
    }

    public Integer durationMinutes() {
        return durationMinutes;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public AssessmentStatus status() {
        return status;
    }

    /** DRAFT-only — see class javadoc for why. */
    public void update(String type, String title, BigDecimal totalMarks, Integer durationMinutes, Instant startsAt, Instant endsAt) {
        if (status != AssessmentStatus.DRAFT) {
            throw new IllegalStateException("assessment definition is immutable once " + status);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (totalMarks != null && totalMarks.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("totalMarks must be > 0");
        }
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("startsAt must be before endsAt");
        }
        this.type = type;
        this.title = title;
        this.totalMarks = totalMarks;
        this.durationMinutes = durationMinutes;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public boolean canTransitionTo(AssessmentStatus target) {
        return switch (status) {
            case DRAFT -> target == AssessmentStatus.SCHEDULED || target == AssessmentStatus.VOIDED;
            case SCHEDULED -> target == AssessmentStatus.OPEN || target == AssessmentStatus.VOIDED;
            case OPEN -> target == AssessmentStatus.CLOSED;
            case CLOSED -> target == AssessmentStatus.PUBLISHED;
            case PUBLISHED, VOIDED -> false;
        };
    }

    public void transitionTo(AssessmentStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("cannot transition assessment from " + status + " to " + target);
        }
        this.status = target;
    }
}
