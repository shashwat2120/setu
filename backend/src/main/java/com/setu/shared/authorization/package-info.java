/**
 * The central authorization contract — {@code can(permission, resource)} —
 * every module authorizes resource access through, per Day 4 instruction
 * §10 ("do not scatter role checks throughout controllers") and §11
 * ("authorization must exist below the HTTP controller layer... so Sprint 6
 * service extraction does not accidentally remove the authorization
 * boundary").
 * <p>
 * Originally this package held only the CONTRACT (interfaces, value types)
 * and the engine lived in identity, because role evaluation is identity's own
 * data (identity.roles, identity.role_permissions, identity.user_roles). Sprint
 * 6A.3 made the engine a library every service embeds: {@link
 * com.setu.shared.authorization.AuthorizationEngine} holds the decision logic
 * and takes its role data from a {@link com.setu.shared.authorization.RoleGrantSource}.
 * Identity's source reads its tables ({@code IdentityRoleGrantSource}); an
 * extracted service's source is a local read model fed by Identity's role
 * events. The engine itself still touches no database.
 * <p>
 * See docs/architecture-notes/authorization-engine.md for the full design
 * write-up (decision algorithm, why ownership/assignment resolution is each
 * resource-owning module's own job, why guardian delegation is a distinct
 * path from ordinary role-based permission grants).
 */
package com.setu.shared.authorization;
