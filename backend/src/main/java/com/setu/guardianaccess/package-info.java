/**
 * Guardian Access module.
 * <p>
 * Explicit, scoped, revocable, audited delegated access grants for guardians, kept separate from the relationship itself. Owns the guardian_access schema.
 * Backed by the PostgreSQL 'guardian_access' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.guardianaccess.application} services, never through
 * {@code com.setu.guardianaccess.domain} or {@code com.setu.guardianaccess.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Guardian Access"
)
package com.setu.guardianaccess;
