package com.setu.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps learning.course_modules (V37). A module belongs to exactly one
 * course section; {@code (section_id, sequence)} is DB-unique, the ordering
 * students see is always {@code ORDER BY sequence}, never insertion order.
 */
@Entity
@Table(name = "course_modules", schema = "learning")
public class CourseModule {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourseModuleStatus status;

    protected CourseModule() {
    }

    public CourseModule(UUID id, UUID tenantId, UUID sectionId, String title, int sequence) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.sectionId = sectionId;
        this.title = title;
        this.sequence = sequence;
        this.status = CourseModuleStatus.ACTIVE;
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

    public int sequence() {
        return sequence;
    }

    public CourseModuleStatus status() {
        return status;
    }

    public void update(String title, int sequence) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = title;
        this.sequence = sequence;
    }

    public void archive() {
        this.status = CourseModuleStatus.ARCHIVED;
    }

    public void reactivate() {
        this.status = CourseModuleStatus.ACTIVE;
    }
}
