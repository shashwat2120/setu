/**
 * Career module.
 * <p>
 * Skills, jobs, applications, and placement records. Owns the career schema.
 * Backed by the PostgreSQL 'career' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.career.application} services, never through
 * {@code com.setu.career.domain} or {@code com.setu.career.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Career"
)
package com.setu.career;
