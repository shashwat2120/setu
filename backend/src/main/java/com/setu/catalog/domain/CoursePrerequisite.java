package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Set;
import java.util.UUID;

/**
 * Maps catalog.course_prerequisites (V23, uq_course_prerequisites_edge added
 * V60). A directed edge in the course prerequisite graph.
 * {@code minimumGrade} is validated against a minimal, deliberately small
 * vocabulary — nothing in this project ever established a canonical letter-
 * grade scale (assessment.grades.grade_code itself has no CHECK constraint
 * either), so the smallest defensible standard US letter scale is used here,
 * disclosed rather than silently invented as something larger. Self-loop is
 * also DB-enforced (chk_course_prerequisites_no_self_loop); this constructor
 * check is defense in depth, not a substitute.
 */
@Entity
@Table(name = "course_prerequisites", schema = "catalog")
public class CoursePrerequisite {

    public static final Set<String> SUPPORTED_MINIMUM_GRADES = Set.of("A", "B", "C", "D", "F");

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "prerequisite_course_id", nullable = false)
    private UUID prerequisiteCourseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private PrerequisiteRuleType ruleType;

    @Column(name = "minimum_grade")
    private String minimumGrade;

    protected CoursePrerequisite() {
    }

    public CoursePrerequisite(UUID id, UUID tenantId, UUID courseId, UUID prerequisiteCourseId,
                               PrerequisiteRuleType ruleType, String minimumGrade) {
        if (courseId.equals(prerequisiteCourseId)) {
            throw new IllegalArgumentException("A course cannot be its own prerequisite");
        }
        if (minimumGrade != null && !SUPPORTED_MINIMUM_GRADES.contains(minimumGrade)) {
            throw new IllegalArgumentException("Unsupported minimum_grade: " + minimumGrade);
        }
        this.id = id;
        this.tenantId = tenantId;
        this.courseId = courseId;
        this.prerequisiteCourseId = prerequisiteCourseId;
        this.ruleType = ruleType;
        this.minimumGrade = minimumGrade;
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

    public UUID prerequisiteCourseId() {
        return prerequisiteCourseId;
    }

    public PrerequisiteRuleType ruleType() {
        return ruleType;
    }

    public String minimumGrade() {
        return minimumGrade;
    }
}
