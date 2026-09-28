package com.setu.shared.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the current tenant from the security context &mdash; and only
 * from there. Every module reads the tenant through this interface; none may
 * accept a tenant id from a request parameter or body for an authorization
 * decision (canonical spec &sect;3, TRD &sect;3 "Tenant column").
 * <p>
 * {@code tenantId()} is empty for a platform-scoped principal acting with no
 * single-tenant context (a {@code SUPER_ADMIN} action, or before a
 * cross-tenant user has selected which tenant to act in &mdash;
 * docs/contradiction-audit.md #3, #5). Application code that requires a
 * tenant must call {@link Optional#orElseThrow()} explicitly rather than
 * silently treating an empty tenant as "no restriction".
 * <p>
 * The real implementation (reading the resolved {@code tenant_id} claim off
 * the authenticated JWT) lands in Sprint 5B alongside the rest of identity/
 * authorization &mdash; this interface is the seam Day 1 establishes so every
 * later module is written against it from the start, not retrofitted.
 */
public interface TenantContext {

    Optional<UUID> tenantId();
}
