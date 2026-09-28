/**
 * Institution module.
 * <p>
 * Colleges, campuses, academic organizations, and departments. Owns the institution schema.
 * Backed by the PostgreSQL 'institution' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.institution.application} services, never through
 * {@code com.setu.institution.domain} or {@code com.setu.institution.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Institution"
)
package com.setu.institution;
