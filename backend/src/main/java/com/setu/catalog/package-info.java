/**
 * Catalog module.
 * <p>
 * Academic years and periods, programs, curricula, courses and prerequisites, scheduling resources, and curriculum-driven degree progress. Owns the catalog schema.
 * Backed by the PostgreSQL 'catalog' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.catalog.application} services, never through
 * {@code com.setu.catalog.domain} or {@code com.setu.catalog.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Catalog"
)
package com.setu.catalog;
