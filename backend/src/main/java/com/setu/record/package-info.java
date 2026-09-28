/**
 * Academic Record module.
 * <p>
 * The student's authoritative academic history: posted term records, transcripts, graduation applications. Consumes GradePublished rather than being folded into Assessment. Owns the record schema.
 * Backed by the PostgreSQL 'record' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.record.application} services, never through
 * {@code com.setu.record.domain} or {@code com.setu.record.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Academic Record"
)
package com.setu.record;
