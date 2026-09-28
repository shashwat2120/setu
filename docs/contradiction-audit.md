# Setu — Contradiction Audit

**Resolution log**: #7 APPROVED (nullable `class_id`), #8 APPROVED (single
primary program, policy-driven dual-program escape hatch), #12 LOCKED
(literal Postgres schema-per-module — see
[schema-per-module-map.md](schema-per-module-map.md)). erd-audit-2.md's
findings E/H1/H2/H3 are also resolved: E approved (drop `api_clients`), H1
approved (`persons` → Identity), H2 approved (no separate Guardian service),
H3 applied with a change (Academic Record is its own Sprint 6 candidate,
`degree_progress` → Institution/Academic) — see
[sprint6-candidate-services.md](sprint6-candidate-services.md) and
[table-ownership-map.md](table-ownership-map.md). Two new open questions
surfaced while applying H3, added as #13/#14 below.

Per [FINAL_CANONICAL_PROJECT_SPEC.md](FINAL_CANONICAL_PROJECT_SPEC.md) §17.7/§17.11:
"identify contradictions before implementation... if scope conflicts reappear,
stop and flag them explicitly." None of these were silently resolved — each
resolution below was made explicit in [ERD.md](ERD.md)/[erd/](erd/) and is
listed here for review before any entity gets coded. **If you disagree with a
resolution, say so before Sprint 5A migrations are written** — every one of
these is a real design decision, not a formality.

## 1. Does `colleges` carry its own `tenant_id`?

**Contradiction**: the canonical spec's shared-columns convention says every
tenant-owned table gets a `tenant_id` column, but `colleges` *is* the tenant
root — a `colleges.tenant_id` column would either be self-referential (`id =
tenant_id`, pointless) or imply colleges themselves belong to some other
tenant (wrong).

**Resolution applied**: `colleges` is the one table exempt from the
`tenant_id` convention. `colleges.id` is used directly as the `tenant_id`
value referenced by every other table's FK. Stated explicitly in ERD.md's
global conventions so it isn't rediscovered as a "missing column" later.

## 2. `persons` ↔ `users` link was undocumented

**Contradiction**: the canonical schema contract lists both `users` (login)
and `persons` (human identity/profile root) but never states how they
connect. Without a link, there's no way to go from a login session to "which
student/faculty/guardian is this."

**Resolution applied**: `persons.user_id UUID NULL UNIQUE REFERENCES
users(id)`. Nullable because a person record can exist before login
activation (e.g. an admin pre-creates a guardian record and invites them);
unique because one login maps to at most one human identity.

**Alternative considered and rejected**: `users.person_id` instead. Rejected
because a single login might arguably need to resolve to different people in
different tenants in a multi-tenant edge case (unlikely but not impossible —
e.g. shared institutional login); keeping the FK on `persons` pointing at
`users` keeps `users` tenant-independent and simple, matching resolution #3.

## 3. Are `users` global or per-tenant?

**Contradiction**: `user_roles` has a composite unique `(user_id, role_id,
tenant_id)` with "optional platform role," which only makes sense if a single
`users` row can hold roles in multiple tenants — but nothing states this
explicitly, and it's easy to assume `users` is tenant-scoped like almost
everything else in this schema.

**Resolution applied**: `users` is a **global, tenant-independent** identity.
Tenant-specific access lives entirely in `user_roles.tenant_id`. This directly
supports the real scenario of a guardian with children at two different
colleges, or a `PLACEMENT_OFFICER` who is also faculty. `student_profiles` /
`faculty_profiles` / `guardians`, by contrast, **are** tenant-scoped (a
student's admission record is meaningless outside its tenant).

## 4. `user_roles` unique constraint gap with NULL `tenant_id`

**Contradiction**: Postgres treats `NULL` as distinct from every other value
in a standard unique index, so `UNIQUE (user_id, role_id, tenant_id)` does
**not** prevent two identical platform-role rows (`tenant_id = NULL`) for the
same user — a real duplicate-grant bug waiting to happen for `SUPER_ADMIN` role assignment.

**Resolution applied**: add a second, partial unique index: `UNIQUE
(user_id, role_id) WHERE tenant_id IS NULL`, alongside the composite unique
for the tenant-scoped case.

## 5. Session / access-token tenant scope

**Contradiction**: the canonical spec's JWT guidance says the access token
carries "tenant context or tenant-independent subject only" — ambiguous about
whether a *session* (refresh token) is tied to one tenant or spans all of a
user's tenants.

**Resolution applied**: `sessions` (refresh tokens) are tenant-agnostic — one
login session, re-usable to mint access tokens for any tenant the user has an
active `user_roles` grant in. Each **access token**, once issued, carries one
resolved `tenant_id` claim for that specific request context. This lets a
cross-tenant user (rare, but real per #3) switch tenant context without a full
re-login, while every individual API call still operates under one
unambiguous tenant.

## 6. Guardian-grant policy ceiling was undefined

**Contradiction**: the source material says a guardian grant's permissions
"cannot exceed policy" but never defines what that policy is or where it's stored.

**Resolution applied**: a tenant-level configured ceiling (initially a fixed
default set — `VIEW_ATTENDANCE`, `VIEW_GRADES`, `VIEW_SCHEDULE` — stored in
`colleges.settings_json`, promotable to its own `guardian_access_policy` table
if per-tenant customization becomes a real requirement). Enforced at
`guardian_access_permissions` insert time in the `guardian-access` module's
application service, not the database (a `CHECK` constraint can't reference
another table's JSON config).

## 7. `course_sections.class_id` nullability — mandatory cohort vs. open elective

**Contradiction**: the Master Blueprint's schema table lists `class_id` on
`course_sections` without stating nullability, but the canonical domain map
separately describes `classes` (cohort) and `course_sections` (offering) as
distinct concepts that "concrete offering of a course" doesn't obviously
require a cohort for (e.g. open electives any student can take).

**Resolution applied**: `class_id` is **nullable**. A section with `class_id`
set is a mandatory, cohort-scheduled offering (every student in that cohort is
expected to take it); a section with `class_id = NULL` is an open elective.
This supports both academic patterns without forcing every elective section
into an artificial cohort assignment.

## 8. `student_programs` — single vs. dual program policy

**Contradiction**: source material states "only one active primary program
unless policy allows dual program" without defining that policy or its
storage.

**Resolution applied**: default is one active primary program, enforced by a
partial unique index `(tenant_id, student_id) WHERE status = 'ACTIVE' AND
is_primary = true`. A tenant setting (`colleges.settings_json ->>
'allow_dual_program'`) may permit additional `is_primary = false` active
programs, which the partial index doesn't constrain — so dual-program support
requires only a policy toggle later, not a schema migration.

## 9. Attendance `token_hash` vs. "never persist raw token"

**Apparent tension, not a real contradiction**: `attendance_sessions.token_hash`
looks at first glance like it conflicts with the guidance that "rotating QR
can be implemented without persisting raw token."

**Resolution applied (clarification, not a design change)**: `token_hash` is
a hash of the *current* rotating token from `attendance_qr_sessions`, refreshed
on every rotation. The raw token itself is never written to either table —
only transmitted to the display and validated against the hash. Documented
explicitly in the ERD so this isn't mis-read as "the raw token is stored" during implementation.

## 10. `assessment_attempts.score` vs. `grades.raw_marks` relationship

**Contradiction**: the schema lists a `score` column directly on
`assessment_attempts` *and* a separate `grades` table keyed by
`(enrollment_id, component_id)` with its own `raw_marks` — with no stated
relationship between the two, leaving it unclear whether they're meant to be
the same value in two places (redundant/inconsistent risk) or genuinely
different things.

**Resolution applied**: they are deliberately different. `assessment_attempts.score`
is the **provisional, attempt-level** result (what an auto-grader computed, or
a first-pass manual score) — it belongs to that one attempt. `grades.raw_marks`
is the **published, official** component-level mark, produced by a distinct
**evaluation step** that may involve moderation and can legally differ from
the raw attempt score. The evaluation step (which module/service performs it,
and its own transaction boundary) is `assessment`'s responsibility, detailed
in [transaction-concurrency-matrix.md](transaction-concurrency-matrix.md).

## 11. Risk score reproducibility — hash alone isn't enough

**Contradiction**: the canonical spec requires risk scores to be
"reproducible from stored inputs" and says to "store... feature snapshot
hash," but a hash alone cannot reconstruct anything — it can only verify a
value you already have. If the hash is the *only* thing stored, "reproducible"
is actually false.

**Resolution applied**: `risk_scores` stores both `feature_snapshot_hash`
(verification checksum) **and** `feature_snapshot JSONB` (the actual feature
values used). The hash guards against silent tampering/drift claims; the JSON
is what makes recalculation actually possible.

## 12. Migration granularity — "schema-per-module" vs. the existing V1 migration

**Contradiction**: the canonical spec's Sprint 5 data row says "PostgreSQL,
schema-per-module" (i.e. separate Postgres `CREATE SCHEMA` namespaces, like
`identity.users`, `catalog.courses`), but the `V1__init.sql` migration already
written for this project (per `AGENTS.md` prompt 0.1) created its sanity table
in the default `public` schema with no module-schema structure.

**Resolution: LOCKED by explicit decision.** Literal Postgres schema-per-module
— 16 (now still 16, table moves don't change schema count) `CREATE SCHEMA`
statements, one per Sprint 5 module. Full detail in
[schema-per-module-map.md](schema-per-module-map.md). `V1__init.sql`'s
content (extension + sanity table) doesn't need to move — it's schema-agnostic;
the schema-creation migrations land as `V2` onward, before any module's real
tables.

## 13. `student_academic_periods` — Sprint 5 resolved, Sprint 6 still open

**Sprint 5 module ownership: RESOLVED.** Per your documentation
clarification: `student_academic_periods` is Record-owned — it represents
the student's official term snapshot, the same character as
`student_academic_records`, regardless of which module's activity produces it.

**Sprint 6 service ownership: still open**, explicitly — it's more
technically coupled to `academic_periods` (Institution/Academic) than
Academic Record's other tables are. Left for Day 16's real coupling
measurement, not this document's reasoning, to decide.

## 14. `graduation_applications` — RESOLVED

Confirmed under Academic Record. Eligibility/curriculum rules stay owned by
Catalog/Institution-Academic (`degree_progress`, `curriculum_requirements`);
Record consumes the eligibility result via an application call at review
time rather than owning or reinterpreting curriculum logic. No longer open.
