# Setu — Authorization Engine (Sprint 5B Day 4)

## Why a central engine, not scattered role checks

Day 4's instruction was explicit: authorization must evaluate principal +
role + permission + tenant + resource + ownership/assignment + workflow
state + action together, never `if (role == ADMIN)`, and it must live below
the HTTP controller layer so Sprint 6 service extraction can't accidentally
strip it out. That rules out per-controller `@PreAuthorize("hasRole(...)")`
checks (they only ever see the role, never ownership or workflow state, and
they live in the layer that's easiest to forget to carry over) and argues
for one reusable `can(permission, resource)` call every application service
makes for itself.

## The contract lives in `shared`, the engine lives in `identity`

- `com.setu.shared.authorization` holds only the contract: `ResourceRef`,
  `AccessDecision`, `AuthorizationContext`, `GuardianDelegationChecker` — no
  database access, matching the Day 4 §12 restriction on what `shared` may
  contain, and mirroring the existing `TenantContext` pattern exactly.
- `com.setu.identity.infrastructure.authorization.AuthorizationService`
  implements `AuthorizationContext`. It lives in `identity` because
  permission/role evaluation is fundamentally identity's own data
  (`identity.roles`, `identity.permissions`, `identity.role_permissions`,
  `identity.user_roles`) — the same reasoning that already put the real
  `TenantContext` implementation there in Day 3's design.

## The decision algorithm

`AuthorizationService.can(permissionCode, resource)`, in order (first
match wins):

1. **Unauthenticated** → deny. The principal is read from
   `SecurityContextHolder`, never passed as a parameter — there is no way
   to check authorization "for" a different user than the one actually
   holding the token.
2. **`resource.workflowBlocked()`** → deny unconditionally, before any
   role/permission/ownership check runs. Permission alone is insufficient
   (Day 4 §7) — a locked course section stays locked even for someone who
   would otherwise have every right to edit it.
3. **Tenant check.** A tenant-scoped resource requires an ACTIVE
   tenant-scoped role in that *exact* tenant (`identity.user_roles.tenant_id
   = resource.tenantId`), unless the principal holds an active
   platform-scoped `SUPER_ADMIN` role. The tenant compared against is
   *always* the resource's own `tenantId`, resolved server-side by whichever
   module built the `ResourceRef` from its own entity's actual `tenant_id`
   column — never a client-supplied value (Day 4 §3: never trust
   tenantId/collegeId from a request body, query parameter, header, or
   frontend state).
4. **Permission check.** Granted either by one of the principal's
   tenant-matching roles (`identity.role_permissions`) or, when the resource
   names a delegation target student and the principal holds an active
   `GUARDIAN` role in that tenant, by an active guardian delegation. Either
   is sufficient; a guardian is never also required to hold the permission
   via a role grant, and a role-holder never needs a guardian grant.
5. **Ownership/assignment check** — skipped when the permission was granted
   via delegation (delegation IS the ownership-equivalent for a guardian)
   and skipped for `SUPER_ADMIN`. Otherwise: if the resource declares an
   owner, the principal must be it or be in the assigned set; if the
   resource declares only an assigned set (no single owner — e.g. a course
   section with several co-teaching faculty), the principal must be in it.

If nothing denies, the result is `Allow`.

## Why `ResourceRef` takes already-resolved data, not raw IDs the engine looks up itself

The engine does not query `enrollment.enrollments`, `directory.
student_profiles`, or any other resource-owning module's tables to figure
out "who owns this." The calling module resolves its own ownership/
assignment from its own entities and hands the engine an already-populated
`ResourceRef`. Two reasons:

1. **Module boundaries.** The engine reaching into arbitrary tables across
   every module would itself be the kind of boundary violation the whole
   exercise exists to prevent, and would make the engine impossible to
   reason about without knowing every module's schema.
2. **Sprint 5B's actual module state.** Enrollment, attendance, learning,
   assessment, and record have no application-layer services yet — only
   their schema exists (Sprint 5A). Day 4 delivers the reusable engine and
   proves it fully against the modules that DO have real application code
   today: identity (self-ownership), and guardian access (delegation, fully
   real, schema and services both). Faculty-assignment and student-ownership
   scenarios in the Day 4 test matrix are proven at the engine level with
   realistic `ResourceRef` values, the same shape a real Day 5+
   `enrollment.application` service would construct from its own
   `faculty_assignments`/`enrollments` rows — the engine's ownership/
   assignment logic is identical either way; only where the attributes come
   from differs.

## Guardian delegation — a distinct path from role-based permission grants

`identity.role_permissions` never grants `VIEW_GRADES`/`VIEW_ATTENDANCE`/
`VIEW_SCHEDULE`/`VIEW_ACADEMIC_STATUS` to the `GUARDIAN` role (see V55's own
comment) — doing so would let any guardian view any student's data. Instead:

```
guardian relationship (directory.student_guardians)
    — records the family relationship, confers NOTHING by itself
guardian_access_grants (guardian_access schema)
    — an active grant for ONE guardian, ONE student, ONE tenant
guardian_access_permissions (guardian_access schema)
    — ONE specific permission code attached to that grant
```

All three — active grant AND a matching permission row AND the resource
naming that exact student — must hold, checked in one call:
`GuardianAccessService.hasActiveDelegatedAccess(guardianId, studentId,
tenantId, permissionCode, now)`. A grant's `isUsable(now)` check is
fail-safe: `status = 'ACTIVE'` AND `(expires_at IS NULL OR expires_at >
now)`, never trusting that a background sweep has already flipped a lapsed
grant's status to `EXPIRED`. Revocation is immediate — the moment
`revoked_at` is set, the very next authorization check denies, with no
caching or delay (see `GuardianDelegationAuthorizationTests.
guardianWithRevokedGrantIsDenied`).

Resolving *which* `directory.guardians` row an authenticated
`identity.users.id` corresponds to composes three modules —
`GuardianDelegationAdapter` (in `identity.infrastructure.authorization`)
calls `identity`'s own `Person` lookup (same module), then
`directory.application.DirectoryService` and
`guardianaccess.application.GuardianAccessService` (both cross-module calls
through each module's `application` layer, the only sanctioned path). This
composition can't live inside `directory` or `guardianaccess` without
creating a dependency the other way, so it lives beside its only caller.

**A real Modulith gap this surfaced**: every module's `application`
package-info already claimed to be "the module's public-facing port," but
Spring Modulith's default visibility rules don't treat a package named
`application` as automatically exposed — only a module's root package, or a
package explicitly marked `@NamedInterface`, counts. This was never caught
before Day 4 because Day 3 never actually made a real cross-module call.
Fixed by adding `@org.springframework.modulith.NamedInterface` to all 16
modules' `application/package-info.java` files, uniformly — not just the
two (`directory`, `guardianaccess`) Day 4 happens to exercise, so Day 5+
doesn't hit the identical surprise.

## Step-up authentication

Sensitive operations (Day 4 §8: role changes, permission changes, passkey
management, security settings, emergency suspension, ...) require a *recent*
fresh passkey reauthentication, not merely a valid access token:

- `POST /auth/step-up/options` / `POST /auth/step-up/finish` — requires an
  already-valid bearer token (unlike the rest of `/auth/**`, which is
  intentionally public — see `SecurityConfig`), completes a real WebAuthn
  ceremony scoped to the caller's own passkeys only
  (`PasskeyAuthenticationService#startStepUpAuthentication`), and on success
  issues a new access token carrying a `sup` (step-up) claim = the
  reauthentication timestamp.
- `AuthorizationContext#hasRecentStepUp()` / `#assertStepUp()` check that
  claim is present and within `app.security.authorization.step-up-validity-
  minutes` (default 5). Ordinary reads never call this — only the specific
  operations that need it (`RoleAssignmentService`, `UserSuspensionService`,
  `PasskeyAuthenticationService#revoke`).
- Step-up is checked **separately** from `can()`, not folded into it — most
  permission checks must NOT require step-up, and conflating the two would
  make it too easy to accidentally gate an ordinary read behind
  reauthentication.

## Audit

`governance.audit_logs` gains rows for every sensitive action this Sprint
touches: `ROLE_ASSIGNED`, `ROLE_REVOKED`, `PASSKEY_REVOKED`,
`GUARDIAN_ACCESS_GRANTED`, `GUARDIAN_ACCESS_REVOKED`, `USER_SUSPENDED`, plus
Day 3's `PASSWORD_VERIFY_SUCCESS`/`FAILURE`, `PASSKEY_AUTH_SUCCESS`/
`FAILURE`, `PASSWORD_RESET_INITIATED`/`COMPLETED`. Every write goes through
`AuditLogWriter`, which never receives raw secrets — callers pass a `Map`
of structured, already-non-sensitive fields (reason codes, target IDs,
permission codes), never a password, key, or token.

## Scope notes (disclosed, not silent)

- `identity.role_permissions` (V53) didn't exist before Day 4 — V8's own
  comment already said the full RBAC catalog was Sprint 5B's job. Domain
  table count moves from 85 to 86; see `MigrationIntegrationTests`.
- Bootstrapping the very first role grant (there is no `SUPER_ADMIN` yet on
  a brand-new platform) is necessarily an operational/migration-seed step,
  not something `RoleAssignmentService` itself can gate — the same bootstrap
  problem every RBAC system has. Tests create the first grant directly via
  `UserRoleRepository`, not through the service.
- Workflow-state authorization for real academic aggregates (course
  sections, grades, enrollment) is proven at the engine level
  (`workflowBlocked` flag) and demonstrated fully end-to-end for the one
  real aggregate Day 4 has to work with — the guardian access grant's own
  ACTIVE → REVOKED/EXPIRED lifecycle. The *pattern* (a domain method decides
  workflow validity, the engine enforces the resulting flag unconditionally)
  is what Day 5+ modules are expected to follow for course sections, grade
  publication, and enrollment closing — not something Day 4 can build ahead
  of those modules existing.
