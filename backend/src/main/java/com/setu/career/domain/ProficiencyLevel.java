package com.setu.career.domain;

/**
 * Shared ordinal scale for career.student_skills.proficiency_level and
 * career.job_skills.required_level (both plain TEXT columns, V45/V46 — no
 * DB CHECK constraint, validated here). Declaration order IS the ordering
 * ({@link Enum#compareTo}) — deliberately small and closed, matching this
 * project's established closed-enum convention (not extended for
 * architecture size, see {@code RoleCode}'s javadoc). This ordering is what makes
 * eligibility checking deterministic and explainable (Day 12 requirement):
 * a student meets a required level exactly when {@code
 * studentLevel.compareTo(requiredLevel) >= 0}.
 */
public enum ProficiencyLevel {
    BEGINNER, INTERMEDIATE, ADVANCED, EXPERT
}
