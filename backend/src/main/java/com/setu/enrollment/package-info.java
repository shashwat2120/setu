/**
 * Enrollment module.
 * <p>
 * Classes and cohorts, course sections, faculty assignments, and the enrollment state machine (capacity, waitlist, overrides). Owns the enrollment schema.
 * Backed by the PostgreSQL 'enrollment' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.enrollment.application} services, never through
 * {@code com.setu.enrollment.domain} or {@code com.setu.enrollment.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Enrollment"
)
package com.setu.enrollment;
