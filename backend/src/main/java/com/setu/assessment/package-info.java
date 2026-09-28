/**
 * Assessment module.
 * <p>
 * Assessment execution, evaluation, and grade production and publication. Stops at publish, does not own the student's official history. Owns the assessment schema.
 * Backed by the PostgreSQL 'assessment' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.assessment.application} services, never through
 * {@code com.setu.assessment.domain} or {@code com.setu.assessment.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Assessment"
)
package com.setu.assessment;
