package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps catalog.courses (V21) — a CATALOG DEFINITION. Not a section, not a
 * class/cohort, not an enrollment (Day 7 §4). Transitions per
 * docs/state-machines.md #9: DRAFT -&gt; ACTIVE -&gt; DEPRECATED -&gt; RETIRED
 * (terminal, one-way), with DEPRECATED -&gt; ACTIVE allowed as a correction
 * (un-deprecate).
 */
@Entity
@Table(name = "courses", schema = "catalog")
public class Course {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "department_id", nullable = false)
    private UUID departmentId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private BigDecimal credits;

    @Column(name = "course_type")
    private String courseType;

    private String level;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourseStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Version
    private int version;

    protected Course() {
    }

    public Course(UUID id, UUID tenantId, UUID departmentId, String code, String title, BigDecimal credits,
                  String courseType, String level, String description, UUID actorUserId, Instant now) {
        if (credits == null || credits.signum() <= 0) {
            throw new IllegalArgumentException("credits must be positive");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.departmentId = departmentId;
        this.code = code;
        this.title = title;
        this.credits = credits;
        this.courseType = courseType;
        this.level = level;
        this.description = description;
        this.status = CourseStatus.DRAFT;
        this.createdAt = now;
        this.createdBy = actorUserId;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID departmentId() {
        return departmentId;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public BigDecimal credits() {
        return credits;
    }

    public String courseType() {
        return courseType;
    }

    public String level() {
        return level;
    }

    public String description() {
        return description;
    }

    public CourseStatus status() {
        return status;
    }

    public void update(String title, BigDecimal credits, String courseType, String level, String description,
                        UUID actorUserId, Instant now) {
        if (credits == null || credits.signum() <= 0) {
            throw new IllegalArgumentException("credits must be positive");
        }
        this.title = title;
        this.credits = credits;
        this.courseType = courseType;
        this.level = level;
        this.description = description;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public boolean canTransitionTo(CourseStatus target) {
        return switch (status) {
            case DRAFT -> target == CourseStatus.ACTIVE;
            case ACTIVE -> target == CourseStatus.DEPRECATED;
            case DEPRECATED -> target == CourseStatus.RETIRED || target == CourseStatus.ACTIVE;
            case RETIRED -> false;
        };
    }

    public void transitionTo(CourseStatus target, UUID actorUserId, Instant now) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition course from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
