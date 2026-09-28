/**
 * Analytics module.
 * <p>
 * Read-optimized projections consumed from domain events across every other module. Owns the analytics schema (empty until Sprint 5F).
 * Backed by the PostgreSQL 'analytics' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.analytics.application} services, never through
 * {@code com.setu.analytics.domain} or {@code com.setu.analytics.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Analytics"
)
package com.setu.analytics;
