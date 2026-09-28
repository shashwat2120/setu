/**
 * Identity module.
 * <p>
 * Authentication identity, credentials, sessions, roles, permissions, and canonical human identity (persons). Owns the identity schema.
 * Backed by the PostgreSQL 'identity' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.identity.application} services, never through
 * {@code com.setu.identity.domain} or {@code com.setu.identity.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Identity"
)
package com.setu.identity;
