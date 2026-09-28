package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps catalog.curriculum_requirement_courses (V22) — tenant_id carried explicitly per its own header comment (joins two independently tenant-owned entities). */
@Entity
@Table(name = "curriculum_requirement_courses", schema = "catalog")
public class CurriculumRequirementCourse {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_role", nullable = false)
    private RequirementRole requirementRole;

    protected CurriculumRequirementCourse() {
    }

    public CurriculumRequirementCourse(UUID id, UUID tenantId, UUID requirementId, UUID courseId, RequirementRole requirementRole) {
        this.id = id;
        this.tenantId = tenantId;
        this.requirementId = requirementId;
        this.courseId = courseId;
        this.requirementRole = requirementRole;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID requirementId() {
        return requirementId;
    }

    public UUID courseId() {
        return courseId;
    }

    public RequirementRole requirementRole() {
        return requirementRole;
    }
}
