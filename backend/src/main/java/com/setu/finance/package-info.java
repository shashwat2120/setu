/**
 * Finance module.
 * <p>
 * Fee structures, charges, payments, and idempotent payment-provider webhook integration. Owns the finance schema.
 * Backed by the PostgreSQL 'finance' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.finance.application} services, never through
 * {@code com.setu.finance.domain} or {@code com.setu.finance.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Finance"
)
package com.setu.finance;
