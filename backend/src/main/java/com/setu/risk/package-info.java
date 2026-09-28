/**
 * Student Success module.
 * <p>
 * The deterministic risk engine: risk scores, ranked evidenced factors, recommendations, interventions. Never mutated by the AI layer. Owns the risk schema.
 * Backed by the PostgreSQL 'risk' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.risk.application} services, never through
 * {@code com.setu.risk.domain} or {@code com.setu.risk.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Student Success"
)
package com.setu.risk;
