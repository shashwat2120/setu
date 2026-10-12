# Setu — PostgreSQL Schema-per-Module Map

Per your **#12 LOCKED** decision: literal Postgres `CREATE SCHEMA` per
module, not one `public` schema. This resolves
[contradiction audit #12](contradiction-audit.md#12-migration-granularity--schema-per-module-vs-the-existing-v1-migration)
— the existing `V1__init.sql` (extension + sanity table, currently in
`public`) needs a follow-up migration establishing this structure before any
module's real tables land (see [migration-order.md](migration-order.md) once
that's produced).

## Why schemas, not just naming discipline

A Postgres schema is a real namespace the database itself enforces — it lets
`GRANT`/`REVOKE` be scoped per module (a future read-only analytics role can
be granted `USAGE` on specific schemas only), keeps `\dt` / tooling output
organized by module without a naming convention everyone has to remember, and
— most importantly for Sprint 6 — makes "what does this module actually own"
a database-verifiable fact (`information_schema` can answer it), not just a
convention that a stray migration could quietly violate.

## The 16 schemas (one per Sprint 5 module, TRD §2)

```sql
CREATE SCHEMA identity;
CREATE SCHEMA directory;
CREATE SCHEMA guardian_access;
CREATE SCHEMA institution;
CREATE SCHEMA catalog;
CREATE SCHEMA enrollment;
CREATE SCHEMA attendance;
CREATE SCHEMA learning;
CREATE SCHEMA assessment;
CREATE SCHEMA record;
CREATE SCHEMA finance;
CREATE SCHEMA career;
CREATE SCHEMA risk;
CREATE SCHEMA notification;
CREATE SCHEMA analytics;
CREATE SCHEMA governance;  -- audit_logs, outbox_events, idempotency_keys (cross-cutting, see below)
```

`ai_explain` has no schema of its own in Sprint 5 — it owns no tables (Table
Ownership Map: it reads `risk.risk_scores`/`risk.risk_factors` through an
authorized application call, never a cross-schema join).

## Table → schema assignment

Directly follows [table-ownership-map.md](table-ownership-map.md)'s "Sprint 5
module" column — each table's schema is its owning module's schema:

| Schema | Tables |
|---|---|
| `identity` | `users, roles, permissions, user_roles, sessions, passkeys, persons` — `persons` moved here per **approved decision H1** |
| `directory` | `student_profiles, faculty_profiles, guardians, student_guardians` — `persons` moved out (H1); these reference `identity.persons(id)` cross-schema |
| `guardian_access` | `guardian_access_grants, guardian_access_permissions` |
| `institution` | `colleges, campuses, academic_organizations, departments` |
| `catalog` | `academic_years, academic_periods, programs, curricula, curriculum_requirements, curriculum_requirement_courses, courses, course_prerequisites, buildings, rooms, meeting_patterns, degree_progress` — `degree_progress` moved here per **approved decision H3** |
| `enrollment` | `classes, class_students, class_faculty, course_sections, faculty_assignments, student_programs, enrollments, enrollment_actions, enrollment_overrides, waitlist_entries` — `student_academic_periods` moved to `record` |
| `attendance` | `attendance_sessions, attendance_qr_sessions, attendance_records, faculty_attendance, faculty_attendance_regularisations, staff_attendance_policies` |
| `learning` | `course_modules, lessons, lesson_resources, learning_progress, assignments, assignment_submissions` |
| `assessment` | `assessments, assessment_components, questions, assessment_attempts, assessment_responses, grades` — stops at grade production (H3); does not include the record-keeping tables below |
| `record` | `student_academic_records, student_academic_periods, transcripts, graduation_applications` — `degree_progress` moved out to `catalog` (H3); `student_academic_periods` moved in (confirmed Record-owned — official term snapshot). This schema maps to the new **Academic Record** Sprint 6 candidate, not folded into Assessment. |
| `finance` | `fee_structures, fee_components, student_charges, student_accounts, payments, payment_events, receipts` |
| `career` | `skills, skill_categories, student_skills, jobs, job_skills, applications, placement_records` |
| `risk` | `risk_scores, risk_factors, recommendations, interventions, intervention_actions` |
| `notification` | `notifications, notification_preferences, delivery_attempts` |
| `governance` | `audit_logs, outbox_events, idempotency_keys` |
| *(none yet)* | `analytics` schema created empty in Sprint 5 — populated by read-model tables once the `analytics` module actually builds projections; not before |

`api_clients` has **no schema entry — removed entirely per approved decision E**,
not merely deferred. Add it as its own migration if a real M2M integration is scoped later.

## Cross-schema references

Postgres allows FKs across schemas within one database freely — this is not
a technical blocker. The discipline that matters is **application-level**:
`enrollment.enrollments.student_id → directory.student_profiles(id)` is a
valid FK the database will enforce, but the `enrollment` module's JPA
repositories must never query `directory`'s tables directly — they call
`directory`'s application service. The schema boundary makes the *data*
ownership explicit and DB-enforceable (grants, `information_schema` audits);
it does not by itself enforce the *code*-level module boundary, which is
Spring Modulith's job (the architecture test from AGENTS.md prompt 0.2).

## `search_path` and JPA implications

Each module's JPA entities should declare their schema explicitly
(`@Table(schema = "identity", name = "users")`) rather than relying on a
connection-level `search_path` — explicit is safer against a future
migration or connection-pool config drift silently resolving an unqualified
table name to the wrong schema.

## Governance schema — the one deliberate exception to "one schema per module"

`audit_logs`, `outbox_events`, and `idempotency_keys` don't belong to any
single module (table-ownership-map.md already notes this) — every module
writes to them as part of its own transactions. Rather than duplicating three
tables into all 15 module schemas, they get one shared `governance` schema
that every module has `INSERT`/`SELECT` privileges on. This is a narrow,
named exception — not a precedent for putting anything else cross-schema.

**At Sprint 6 extraction**, each extracted service takes **its own copy** of
whichever of these three tables it needs (`outbox_events` per canonical spec
§2 "service-owned data") — the shared `governance` schema is a Sprint 5
monolith convenience, not the Sprint 6 target shape.
