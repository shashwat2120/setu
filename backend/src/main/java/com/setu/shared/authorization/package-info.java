/**
 * The central authorization contract — {@code can(permission, resource)} —
 * every module authorizes resource access through, per Day 4 instruction
 * §10 ("do not scatter role checks throughout controllers") and §11
 * ("authorization must exist below the HTTP controller layer... so Sprint 6
 * service extraction does not accidentally remove the authorization
 * boundary").
 * <p>
 * Like {@link com.setu.shared.tenant.TenantContext}, this package holds only
 * the CONTRACT (interfaces, value types) — no database access, no business
 * logic (Day 4 instruction §12, {@code shared} restriction). The real
 * engine, {@code AuthorizationService}, lives in
 * {@code com.setu.identity.infrastructure.authorization} because
 * permission/role evaluation is fundamentally identity's own data
 * (identity.roles, identity.permissions, identity.role_permissions,
 * identity.user_roles) — the same reasoning that already put the real
 * {@code TenantContext} implementation there.
 * <p>
 * See docs/architecture-notes/authorization-engine.md for the full design
 * write-up (decision algorithm, why ownership/assignment resolution is each
 * resource-owning module's own job, why guardian delegation is a distinct
 * path from ordinary role-based permission grants).
 */
package com.setu.shared.authorization;
