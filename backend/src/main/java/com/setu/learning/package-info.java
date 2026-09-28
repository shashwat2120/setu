/**
 * Learning module.
 * <p>
 * LMS content: modules, lessons, resources, learning progress, assignments. Owns the learning schema.
 * Backed by the PostgreSQL 'learning' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.learning.application} services, never through
 * {@code com.setu.learning.domain} or {@code com.setu.learning.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Learning"
)
package com.setu.learning;
