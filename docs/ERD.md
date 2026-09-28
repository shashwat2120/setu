# Setu — Final ERD (implementation-level)

Status: pre-coding design artifact, per [FINAL_CANONICAL_PROJECT_SPEC.md](./FINAL_CANONICAL_PROJECT_SPEC.md)
§17.5/§18. No JPA entities, repositories, controllers, services, or migrations
exist yet — this is the design that precedes them.

This file holds shared conventions and the **Identity & People** module. Other
modules are in `docs/erd/`:

- [docs/erd/02-academic.md](erd/02-academic.md) — Tenancy, Academic, Operations
- [docs/erd/03-learning-assessment-records.md](erd/03-learning-assessment-records.md) — Attendance, LMS, Assessment, Record
- [docs/erd/04-finance-career-success-platform.md](erd/04-finance-career-success-platform.md) — Finance, Career, Success, Communication, Governance

See [docs/contradiction-audit.md](contradiction-audit.md) for every ambiguity
found while producing this ERD and its proposed resolution — do not assume a
gap below was accidentally skipped; check the audit before flagging it again.

## Global conventions (stated once, not repeated per table)

- **PK**: `id UUID NOT NULL DEFAULT gen_random_uuid()` on every business entity
  unless a table's entry says otherwise.
- **Tenant ownership**: a tenant-owned table carries `tenant_id UUID NOT NULL
  REFERENCES colleges(id)`. `colleges` itself is the tenant root and does **not**
  carry a `tenant_id` column — `colleges.id` *is* the tenant identifier used
  everywhere else (see contradiction audit #1).
- **Audit columns**: every mutable tenant-owned table carries `created_at
  TIMESTAMPTZ NOT NULL DEFAULT now(), created_by UUID NOT NULL REFERENCES
  users(id), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID NOT
  NULL REFERENCES users(id)`, unless noted otherwise (e.g. system-only tables
  populated by triggers/consumers, not a user action).
- **Optimistic locking**: `version INT NOT NULL DEFAULT 0` on every table
  flagged "mutable aggregate" below; enforced via JPA `@Version` /
  `WHERE version = :expected` on update.
- **Soft delete**: not used by default. Lifecycle is modeled with an explicit
  `status` column and a state machine (see [state-machines.md](state-machines.md)),
  never a `deleted_at` flag on academically, financially, or legally significant
  rows. The few tables where a real soft-delete is appropriate say so explicitly.
- **Historical data**: tables holding officially-posted academic/financial facts
  are **append-only** — corrections add a new row/version, never `UPDATE` the
  original. Each such table says so explicitly.
- **Money**: stored as `NUMERIC(12,2)` with a separate `currency CHAR(3)`
  (ISO 4217), never `FLOAT`/`DOUBLE`.
- **Timestamps**: always `TIMESTAMPTZ`, never naive `TIMESTAMP`.

---

## Module: identity

Owns authentication identity only — never academic/institutional data. See
[contradiction audit #2](contradiction-audit.md#2-persons--users-link-was-undocumented)
for how this links to `persons` in the People sub-module below.

### `users`

- **Purpose**: the login identity. Deliberately minimal — no name, no academic
  data. One `users` row can hold roles across multiple tenants (see
  [audit #3](contradiction-audit.md#3-are-users-global-or-per-tenant)).
- **PK**: `id UUID`
- **FK**: none
- **Columns**: `email CITEXT NOT NULL`, `phone TEXT NULL`, `username CITEXT
  NULL`, `status TEXT NOT NULL DEFAULT 'ACTIVE'`, `email_verified_at TIMESTAMPTZ
  NULL`, `last_login_at TIMESTAMPTZ NULL`
- **Nullability**: `email` NOT NULL; `phone`/`username` nullable
- **Cardinality**: 1 user → N `sessions`, N `passkeys`, N `user_roles`; 0..1
  `persons` (a pure `SUPER_ADMIN` may have no person/profile)
- **Unique**: `email` (case-insensitive via `CITEXT`); `username` — partial
  unique `WHERE username IS NOT NULL`
- **Composite unique**: none
- **Indexes**: `email` (login lookup, high volume), `status`
- **Check constraints**: `status IN ('ACTIVE','SUSPENDED','DEACTIVATED')`
- **Tenant ownership**: none — global identity, not tenant-owned
- **Audit fields**: `created_at`, `updated_at` only (no `created_by` — self
  registration; no tenant)
- **Version/locking**: yes — concurrent status changes / session revocation race
- **Soft delete**: no — `status = 'DEACTIVATED'`, never deleted (referenced by
  audit history in every tenant the user ever touched)
- **Historical/versioning**: status transitions are audit-logged (`audit_logs`),
  not versioned on this row
- **Transaction boundary**: single-row write, `identity` module
- **Owning module**: `identity`
- **High-volume query patterns**: lookup by `email` at login (needs the index
  above); lookup by `id` to resolve the JWT principal on every authenticated
  request — this must be Redis-cached, not a DB hit per request

### `roles`

- **Purpose**: static role catalog
- **PK**: `id UUID`
- **Columns**: `code TEXT NOT NULL`, `name TEXT NOT NULL`, `scope_type TEXT NOT NULL`
- **Check**: `code IN ('SUPER_ADMIN','COLLEGE_ADMIN','FACULTY','STUDENT',
  'PLACEMENT_OFFICER','GUARDIAN')`; `scope_type IN ('PLATFORM','TENANT')`
  (`SUPER_ADMIN` is `PLATFORM`; the rest are `TENANT`)
- **Unique**: `code`
- **Cardinality**: 1 role → N `user_roles`
- **Tenant ownership**: none — platform-wide reference data, seeded not user-created in v1
- **Version/locking**: no — effectively immutable reference table
- **Transaction boundary / owning module**: `identity`
- **Query pattern**: read-heavy, cacheable in full (small, static)

### `permissions`

- **Purpose**: fine-grained permission catalog (`resource` + `action` pairs)
- **PK**: `id UUID`
- **Columns**: `code TEXT NOT NULL`, `resource TEXT NOT NULL`, `action TEXT NOT NULL`
- **Unique**: `code`; **composite unique**: `(resource, action)`
- **Tenant ownership**: none — reference data
- **Owning module**: `identity`

### `user_roles`

- **Purpose**: grants a role to a user within a tenant (or platform-wide)
- **PK**: `id UUID`
- **FK**: `user_id → users(id) NOT NULL`, `role_id → roles(id) NOT NULL`,
  `tenant_id → colleges(id) NULL` — **NULL only** for `PLATFORM`-scope roles
- **Columns**: `starts_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `ends_at
  TIMESTAMPTZ NULL`
- **Cardinality**: N:M `users` ↔ `roles`, scoped per tenant
- **Composite unique**: `(user_id, role_id, tenant_id)` — **plus** a partial
  unique index `(user_id, role_id) WHERE tenant_id IS NULL`, because Postgres
  treats `NULL` as distinct in a normal unique index and would otherwise allow
  duplicate platform-role grants (caught in [audit #4](contradiction-audit.md#4-user_roles-unique-constraint-gap-with-null-tenant_id))
- **Indexes**: `(user_id)` — "all my roles" is the hottest lookup on this table,
  hit on every request to resolve authorization context (Redis-cache the result)
- **Check**: `ends_at IS NULL OR ends_at > starts_at`
- **Tenant ownership**: yes, nullable (see above)
- **Version/locking**: no — rows are added/ended, not mutated in place
- **Transaction boundary**: `identity`
- **Owning module**: `identity`

### `sessions`

- **Purpose**: refresh-token session records, independent of tenant (see
  [audit #5](contradiction-audit.md#5-sessionaccess-token-tenant-scope))
- **PK**: `id UUID`
- **FK**: `user_id → users(id) NOT NULL`
- **Columns**: `refresh_token_hash TEXT NOT NULL`, `expires_at TIMESTAMPTZ NOT
  NULL`, `revoked_at TIMESTAMPTZ NULL`, `device_metadata JSONB NULL`,
  `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- **Unique**: `refresh_token_hash`
- **Indexes**: `(user_id, expires_at)` — revoke-all-for-user, expiry sweep
- **Tenant ownership**: none (see audit #5 — access tokens carry the resolved
  tenant claim, not the session)
- **Version/locking**: no
- **Soft delete**: no — `revoked_at` is the lifecycle field
- **Transaction boundary**: `identity`, single-row
- **Owning module**: `identity`
- **Query pattern**: refresh-token rotation is a point lookup by hash — must be
  fast (indexed, ideally Redis-accelerated per TRD §9), and reuse-after-rotation
  must be detectable (rotate-and-invalidate, flag the whole token family on reuse)

### `passkeys`

- **Purpose**: WebAuthn credentials
- **PK**: `id UUID`
- **FK**: `user_id → users(id) NOT NULL`
- **Columns**: `credential_id BYTEA NOT NULL`, `public_key BYTEA NOT NULL`,
  `sign_count BIGINT NOT NULL DEFAULT 0`, `aaguid UUID NULL`, `transports
  TEXT[] NULL`, `backed_up BOOLEAN NOT NULL DEFAULT false`, `created_at
  TIMESTAMPTZ NOT NULL DEFAULT now()`, `last_used_at TIMESTAMPTZ NULL`,
  `revoked_at TIMESTAMPTZ NULL`, `status TEXT NOT NULL DEFAULT 'ACTIVE'`
- **Unique**: `credential_id` (globally unique per WebAuthn spec, not just per user)
- **Check**: `status IN ('ACTIVE','REVOKED')`
- **Indexes**: `(user_id) WHERE status = 'ACTIVE'`
- **Tenant ownership**: none
- **Version/locking**: no — `sign_count` update is its own monotonic check
  (reject if new `sign_count <= stored sign_count` — clone-detection per WebAuthn)
- **Transaction boundary / owning module**: `identity`
- **Never store**: the private key — it never leaves the authenticator

### `persons`

- **Purpose**: shared human identity — the PII root that a student/faculty/
  guardian profile hangs off, and the link back to a login account. **Owned by
  `identity`, not `directory`** — approved decision H1: "Person identity !=
  domain profile != authentication account," and Identity owns canonical
  human identity (`users`, credentials, sessions, roles/permissions,
  `persons`) as one coherent group, distinct from the domain-specific profile
  tables below.
- **PK**: `id UUID`
- **FK**: `user_id → users(id) NULL` (nullable: a person can exist — e.g.
  invited by admin — before they ever activate login credentials)
- **Columns**: `legal_name TEXT NOT NULL`, `preferred_name TEXT NULL`, `dob DATE
  NULL`, `gender TEXT NULL`, `email CITEXT NULL`, `phone TEXT NULL`,
  `address_json JSONB NULL`
- **Unique**: `user_id` (partial, `WHERE user_id IS NOT NULL`) — at most one
  person per login account
- **Cardinality**: 1 person → 0..1 `student_profiles` (per tenant), 0..1
  `faculty_profiles` (per tenant), 0..1 `guardians` (per tenant) — a person
  *could* legitimately be a guardian at one college and faculty at another
- **Tenant ownership**: **none directly** — `persons` is the tenant-independent
  identity; the profile tables below are where tenant scoping happens (a person
  with children at two colleges is one `persons` row, two `student_guardians`
  rows in two tenants)
- **PII access control**: `address_json`/`dob`/`gender` are sensitive —
  application-layer field-level authorization, not just row-level (canonical
  spec §6 tenant access table: "Parent/PII visibility" varies by role even
  within a tenant)
- **Version/locking**: yes
- **Transaction boundary / owning module**: `identity`
- **Cross-module reference**: `directory.student_profiles` /
  `faculty_profiles` / `guardians` each hold `person_id → identity.persons(id)`
  — a cross-schema FK the database enforces, but `directory` must resolve a
  person's details through `identity`'s application service, never a direct
  cross-schema join in application code (same rule as
  `institution.departments.hod_user_id → identity.users`).

---

## Module: directory (people & profiles)

Distinct from `identity` — see [audit #2](contradiction-audit.md#2-persons--users-link-was-undocumented).
`directory` owns the domain-specific profile tables (`student_profiles`,
`faculty_profiles`, `guardians`, `student_guardians`); `persons`, the shared
identity root they all reference, is owned by `identity` (approved decision H1, above).

### `student_profiles`

- **Purpose**: the academic identity of a student within one tenant
- **PK**: `id UUID`
- **FK**: `tenant_id → colleges(id) NOT NULL`, `person_id → persons(id) NOT NULL`
- **Columns**: `admission_no TEXT NOT NULL`, `roll_no TEXT NULL`, `status TEXT
  NOT NULL DEFAULT 'ACTIVE'`, `admission_date DATE NOT NULL`
- **Check**: `status IN ('ACTIVE','ON_LEAVE','SUSPENDED','GRADUATED','WITHDRAWN')`
- **Composite unique**: `(tenant_id, admission_no)`; `(tenant_id, person_id)` —
  one student profile per person per tenant
- **Cardinality**: 1:0..1 `persons` per tenant; 1 → N `student_programs`, N
  `enrollments`, N `attendance_records` (via enrollment), N `student_guardians`
- **Indexes**: `(tenant_id, roll_no)`
- **Version/locking**: yes
- **Transaction boundary / owning module**: `directory`

### `faculty_profiles`

- **PK**: `id UUID`
- **FK**: `tenant_id → colleges(id) NOT NULL`, `person_id → persons(id) NOT NULL`
- **Columns**: `employee_no TEXT NOT NULL`, `designation TEXT NULL`, `status
  TEXT NOT NULL DEFAULT 'ACTIVE'`, `joined_at DATE NOT NULL`
- **Check**: `status IN ('ACTIVE','ON_LEAVE','SUSPENDED','TERMINATED')`
- **Composite unique**: `(tenant_id, employee_no)`; `(tenant_id, person_id)`
- **Version/locking**: yes
- **Owning module**: `directory`

### `guardians`

- **Purpose**: the guardian's own profile (occupation etc.) — **not** the
  relationship or the access grant, which are separate tables (canonical spec
  §3: "relationships and delegated access are separate")
- **PK**: `id UUID`
- **FK**: `tenant_id → colleges(id) NOT NULL`, `person_id → persons(id) NOT NULL`
- **Columns**: `occupation TEXT NULL`, `organization TEXT NULL`, `address_json
  JSONB NULL`, `status TEXT NOT NULL DEFAULT 'ACTIVE'`
- **Composite unique**: `(tenant_id, person_id)`
- **Owning module**: `directory`

### `student_guardians`

- **Purpose**: the **relationship** (who is whose guardian) — separate from
  what that guardian can actually *see* (`guardian_access_grants`, next module)
- **PK**: `id UUID`
- **FK**: `tenant_id NOT NULL`, `student_id → student_profiles(id) NOT NULL`,
  `guardian_id → guardians(id) NOT NULL`
- **Columns**: `relationship_type TEXT NOT NULL`, `is_primary BOOLEAN NOT NULL
  DEFAULT false`, `is_emergency_contact BOOLEAN NOT NULL DEFAULT false`,
  `notification_flags JSONB NOT NULL DEFAULT '{}'`
- **Composite unique**: `(student_id, guardian_id, relationship_type)`
- **Cardinality**: N:M `student_profiles` ↔ `guardians`
- **Owning module**: `directory`

---

## Module: guardian-access

Deliberately separate from `directory` in transaction-boundary terms even
though it's a small module — access grants are security-sensitive and audited
independently (canonical spec §3: "access is explicit, scoped, revocable and audited").

### `guardian_access_grants`

- **Purpose**: the actual, revocable delegation of visibility — a row, never a
  boolean flag (canonical spec §3, TRD §3)
- **PK**: `id UUID`
- **FK**: `tenant_id NOT NULL`, `student_id → student_profiles(id) NOT NULL`,
  `guardian_id → guardians(id) NOT NULL`, `granted_by → users(id) NOT NULL`
- **Columns**: `status TEXT NOT NULL DEFAULT 'ACTIVE'`, `granted_at TIMESTAMPTZ
  NOT NULL DEFAULT now()`, `expires_at TIMESTAMPTZ NULL`, `revoked_at
  TIMESTAMPTZ NULL`
- **Check**: `status IN ('ACTIVE','EXPIRED','REVOKED')`; `expires_at IS NULL OR
  expires_at > granted_at`
- **Indexes**: `(guardian_id) WHERE status = 'ACTIVE'` — hit on every guardian
  request to resolve visible students
- **Version/locking**: yes — revocation must win a race against a concurrent read
- **Transaction boundary**: revocation is a single-row update but must
  invalidate any cached authorization decision immediately (no stale-cache
  window where a revoked guardian still reads data) — see
  [transaction-concurrency-matrix.md](transaction-concurrency-matrix.md)
- **Owning module**: `guardian-access`

### `guardian_access_permissions`

- **Purpose**: which specific permissions a grant carries (`VIEW_GRADES`,
  `VIEW_ATTENDANCE`, `VIEW_SCHEDULE`, …) — a grant with zero rows here grants
  nothing
- **PK**: `id UUID`
- **FK**: `grant_id → guardian_access_grants(id) NOT NULL`, `permission_code →
  permissions(code) NOT NULL`
- **Composite unique**: `(grant_id, permission_code)`
- **Check**: at insert time (application-enforced, see
  [audit #6](contradiction-audit.md#6-guardian-grant-policy-ceiling-was-undefined)),
  `permission_code` must be within the tenant's configured guardian-permission
  ceiling — never `VIEW_PAYMENT_METHODS` or anything beyond the read-only scope
  the canonical spec's "guardian master-switch access" ban (§15) rules out
- **Owning module**: `guardian-access`
