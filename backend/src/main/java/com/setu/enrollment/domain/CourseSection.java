package com.setu.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps enrollment.course_sections (V28) — a CONCRETE OPERATIONAL OFFERING of
 * a course for one academic period, not the catalog definition itself (Day
 * 7 §6). {@code classId} is intentionally nullable (an open elective has
 * none — docs/contradiction-audit.md #7); this entity never forces one.
 * Transitions per docs/state-machines.md #10: DRAFT -&gt; OPEN -&gt; CLOSED,
 * DRAFT|OPEN -&gt; CANCELLED (terminal), CLOSED -&gt; OPEN allowed as an
 * audited correction within the same registration window (that window
 * check is the caller's job — CourseSectionService — not this entity's,
 * since it needs the academic period's own dates).
 */
@Entity
@Table(name = "course_sections", schema = "enrollment")
public class CourseSection {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "period_id", nullable = false)
    private UUID periodId;

    @Column(name = "class_id")
    private UUID classId;

    @Column(name = "section_code", nullable = false)
    private String sectionCode;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "room_id")
    private UUID roomId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourseSectionStatus status;

    @Column(name = "registration_open_at")
    private Instant registrationOpenAt;

    @Column(name = "registration_close_at")
    private Instant registrationCloseAt;

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

    protected CourseSection() {
    }

    public CourseSection(UUID id, UUID tenantId, UUID courseId, UUID periodId, UUID classId, String sectionCode,
                          int capacity, UUID roomId, Instant registrationOpenAt, Instant registrationCloseAt,
                          UUID actorUserId, Instant now) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (registrationOpenAt != null && registrationCloseAt != null
            && !registrationCloseAt.isAfter(registrationOpenAt)) {
            throw new IllegalArgumentException("registration_close_at must be after registration_open_at");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.courseId = courseId;
        this.periodId = periodId;
        this.classId = classId;
        this.sectionCode = sectionCode;
        this.capacity = capacity;
        this.roomId = roomId;
        this.status = CourseSectionStatus.DRAFT;
        this.registrationOpenAt = registrationOpenAt;
        this.registrationCloseAt = registrationCloseAt;
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

    public UUID courseId() {
        return courseId;
    }

    public UUID periodId() {
        return periodId;
    }

    public UUID classId() {
        return classId;
    }

    public String sectionCode() {
        return sectionCode;
    }

    public UUID roomId() {
        return roomId;
    }

    public int capacity() {
        return capacity;
    }

    public CourseSectionStatus status() {
        return status;
    }

    public Instant registrationOpenAt() {
        return registrationOpenAt;
    }

    public Instant registrationCloseAt() {
        return registrationCloseAt;
    }

    public void changeRoom(UUID roomId, UUID actorUserId, Instant now) {
        this.roomId = roomId;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public void update(String sectionCode, int capacity, Instant registrationOpenAt, Instant registrationCloseAt,
                        UUID actorUserId, Instant now) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (registrationOpenAt != null && registrationCloseAt != null
            && !registrationCloseAt.isAfter(registrationOpenAt)) {
            throw new IllegalArgumentException("registration_close_at must be after registration_open_at");
        }
        this.sectionCode = sectionCode;
        this.capacity = capacity;
        this.registrationOpenAt = registrationOpenAt;
        this.registrationCloseAt = registrationCloseAt;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public boolean canTransitionTo(CourseSectionStatus target) {
        return switch (status) {
            case DRAFT -> target == CourseSectionStatus.OPEN || target == CourseSectionStatus.CANCELLED;
            case OPEN -> target == CourseSectionStatus.CLOSED || target == CourseSectionStatus.CANCELLED;
            case CLOSED -> target == CourseSectionStatus.OPEN; // audited correction, same registration window — caller's job to check the window
            case CANCELLED -> false;
        };
    }

    public void transitionTo(CourseSectionStatus target, UUID actorUserId, Instant now) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition course section from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
