package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Maps catalog.curriculum_requirements (V20) — a child row of one curriculum, no separate tenant_id (V20's own comment: child of a tenant-owned parent). */
@Entity
@Table(name = "curriculum_requirements", schema = "catalog")
public class CurriculumRequirement {

    @Id
    private UUID id;

    @Column(name = "curriculum_id", nullable = false)
    private UUID curriculumId;

    @Column(name = "group_code", nullable = false)
    private String groupCode;

    @Column(name = "min_credits")
    private java.math.BigDecimal minCredits;

    @Column(name = "min_courses")
    private Integer minCourses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_json", columnDefinition = "jsonb", nullable = false)
    private String ruleJson;

    protected CurriculumRequirement() {
    }

    public CurriculumRequirement(UUID id, UUID curriculumId, String groupCode, java.math.BigDecimal minCredits,
                                  Integer minCourses, String ruleJson) {
        if (minCredits != null && minCredits.signum() < 0) {
            throw new IllegalArgumentException("min_credits must be non-negative");
        }
        if (minCourses != null && minCourses < 0) {
            throw new IllegalArgumentException("min_courses must be non-negative");
        }
        this.id = id;
        this.curriculumId = curriculumId;
        this.groupCode = groupCode;
        this.minCredits = minCredits;
        this.minCourses = minCourses;
        this.ruleJson = ruleJson == null ? "{}" : ruleJson;
    }

    public UUID id() {
        return id;
    }

    public UUID curriculumId() {
        return curriculumId;
    }

    public String groupCode() {
        return groupCode;
    }

    public java.math.BigDecimal minCredits() {
        return minCredits;
    }

    public Integer minCourses() {
        return minCourses;
    }

    public String ruleJson() {
        return ruleJson;
    }

    public void update(String groupCode, java.math.BigDecimal minCredits, Integer minCourses, String ruleJson) {
        if (minCredits != null && minCredits.signum() < 0) {
            throw new IllegalArgumentException("min_credits must be non-negative");
        }
        if (minCourses != null && minCourses < 0) {
            throw new IllegalArgumentException("min_courses must be non-negative");
        }
        this.groupCode = groupCode;
        this.minCredits = minCredits;
        this.minCourses = minCourses;
        this.ruleJson = ruleJson == null ? "{}" : ruleJson;
    }
}
