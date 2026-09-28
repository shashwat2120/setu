/**
 * Directory module.
 * <p>
 * Student, faculty, and guardian profile data, distinct from identity accounts. Owns the directory schema.
 * Backed by the PostgreSQL 'directory' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.directory.application} services, never through
 * {@code com.setu.directory.domain} or {@code com.setu.directory.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Directory"
)
package com.setu.directory;
