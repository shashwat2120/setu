package com.setu.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps learning.assignments (V38). Ownership flows through {@code
 * sectionId} (ERD note), not a module link. {@code latePolicy} is raw JSON
 * — no canonical late-policy algorithm is documented anywhere (Day 10
 * instruction explicitly permits the smallest defensible behavior,
 * disclosed): {@code {"allowLate": true|false}}, interpreted by {@code
 * AssignmentSubmissionService} at submit time. {@code null}/absent is
 * treated as {@code allowLate: true} (late accepted, marked {@code LATE}),
 * matching state-machines.md #16's default path ({@code DRAFT → LATE}
 * after {@code due_at}) rather than silently rejecting late work when no
 * policy was ever configured.
 */
@Entity
@Table(name = "assignments", schema = "learning")
public class Assignment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(nullable = false)
    private String title;

    @Column(name = "max_marks")
    private BigDecimal maxMarks;

    @Column(name = "due_at")
    private Instant dueAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "late_policy", columnDefinition = "jsonb")
    private String latePolicy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentStatus status;

    protected Assignment() {
    }

    public Assignment(UUID id, UUID tenantId, UUID sectionId, String title, BigDecimal maxMarks, Instant dueAt,
                       String latePolicy) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (maxMarks != null && maxMarks.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxMarks must be > 0");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.sectionId = sectionId;
        this.title = title;
        this.maxMarks = maxMarks;
        this.dueAt = dueAt;
        this.latePolicy = latePolicy;
        this.status = AssignmentStatus.DRAFT;
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

    public String title() {
        return title;
    }

    public BigDecimal maxMarks() {
        return maxMarks;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public String latePolicy() {
        return latePolicy;
    }

    public AssignmentStatus status() {
        return status;
    }

    public void update(String title, BigDecimal maxMarks, Instant dueAt, String latePolicy) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (maxMarks != null && maxMarks.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxMarks must be > 0");
        }
        this.title = title;
        this.maxMarks = maxMarks;
        this.dueAt = dueAt;
        this.latePolicy = latePolicy;
    }

    public boolean canTransitionTo(AssignmentStatus target) {
        return switch (this.status) {
            case DRAFT -> target == AssignmentStatus.PUBLISHED;
            case PUBLISHED -> target == AssignmentStatus.CLOSED;
            case CLOSED -> false;
        };
    }

    public void transitionTo(AssignmentStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("cannot transition assignment from " + status + " to " + target);
        }
        this.status = target;
    }
}
