# Setu — Migration Order

Ordered Flyway migration plan respecting FK dependencies, schemas,
extensions, seed data, indexes, and constraints. **This is a plan, not SQL**
— per your instruction, no migrations are generated yet.

Naming: `V{n}__{description}.sql`, one logical change per file,
schema-per-module (per the #12 LOCKED decision — see
[schema-per-module-map.md](schema-per-module-map.md)).

## Special-ordering / backfill notes (read before the list)

- **Schemas must exist before any table migration** — `V2` creates all 16
  schemas in one file; every subsequent migration targets an already-existing
  schema.
- **`V1__init.sql` already exists** (extension + sanity table, currently in
  `public`) — untouched, schema-agnostic, stays first.
- **Reference/seed data** (`roles`, `permissions`) must land *after* their
  table migration but *before* any table that FKs to them expects rows
  (`user_roles`) — seeding is its own migration, not folded into the
  `CREATE TABLE` migration, so a re-seed (e.g. adding a new permission later)
  is its own trackable change.
- **`meeting_patterns`' `EXCLUDE` constraint** (room/faculty overlap
  prevention, flagged in erd/02-academic.md) needs the `btree_gist` extension
  enabled *before* that table's migration — an extra early migration, not
  assumed available.
- **No backfill needed anywhere** — this is a from-scratch schema, not a
  migration of existing data. If a future migration ever needs one (e.g.
  computing `student_academic_periods.posted_at` for historical rows), that
  migration must be written as an explicit, reviewed data migration, never
  silently bundled into a schema-change migration.

## Order

Note on sequencing: `identity` and `institution` are interleaved below rather
than fully module-grouped, because `identity.user_roles.tenant_id →
institution.colleges(id)` means `colleges` must exist before `user_roles` can
land — the FK graph drives real ordering here, not the module list. (Caught
while first drafting this list in module-grouped order and corrected before
finalizing — noted so the reasoning is visible, not because two orderings are
both valid.)

```
V1   (existing) — pgvector extension, sanity table, public schema
V2   — CREATE SCHEMA × 16 (identity, directory, guardian_access, institution,
       catalog, enrollment, attendance, learning, assessment, record,
       finance, career, risk, notification, analytics, governance)
V3   — CREATE EXTENSION btree_gist  (needed by catalog.meeting_patterns' EXCLUDE constraint later)
V4   — governance schema: audit_logs, outbox_events, idempotency_keys
       (created early — every later module's migrations reference these
       conceptually, and seeding/testing wants them available first)

--- identity + institution, interleaved per the FK note above ---
V5   — identity.users
V6   — institution.colleges
V7   — institution.campuses, institution.academic_organizations, institution.departments
       (departments.hod_user_id → users — fine, users already exists)
V8   — identity.roles, identity.permissions
V9   — seed: roles + permissions reference data
V10  — identity.user_roles  (now both users and colleges exist)
V11  — identity.sessions
V12  — identity.passkeys
V13  — identity.persons

--- directory + guardian_access schemas ---
V14  — directory.student_profiles, directory.faculty_profiles, directory.guardians
V15  — directory.student_guardians
V16  — guardian_access.guardian_access_grants
V17  — guardian_access.guardian_access_permissions

--- catalog schema ---
V18  — catalog.academic_years, catalog.academic_periods
V19  — catalog.programs
V20  — catalog.curricula, catalog.curriculum_requirements
V21  — catalog.courses
V22  — catalog.curriculum_requirement_courses  (FK: curriculum_requirements, courses — both now exist)
V23  — catalog.course_prerequisites
V24  — catalog.buildings, catalog.rooms
V25  — catalog.degree_progress  (FK: student_profiles, curricula — both exist by now)

--- enrollment schema ---
V26  — enrollment.classes
V27  — enrollment.class_students, enrollment.class_faculty
V28  — enrollment.course_sections  (FK: courses, academic_periods, classes, rooms)
V29  — catalog.meeting_patterns  (FK: course_sections — must come after V28
       despite being a `catalog` table; cross-schema ordering follows FK
       dependency, not schema grouping. Includes the `EXCLUDE USING gist`
       constraint, requiring V3's `btree_gist` extension.)
V30  — enrollment.faculty_assignments
V31  — enrollment.student_programs
V32  — enrollment.enrollments
V33  — enrollment.enrollment_actions, enrollment.enrollment_overrides, enrollment.waitlist_entries

--- attendance schema ---
V34  — attendance.attendance_sessions  (FK: course_sections, meeting_patterns)
V35  — attendance.attendance_qr_sessions
V36  — attendance.attendance_records  (FK: attendance_sessions, enrollments)

--- learning schema ---
V37  — learning.course_modules  (FK: course_sections)
V38  — learning.lessons, learning.lesson_resources
V39  — learning.learning_progress
V40  — learning.assignments
V41  — learning.assignment_submissions

--- assessment schema ---
V42  — assessment.assessments  (FK: course_sections)
V43  — assessment.assessment_components
V44  — assessment.questions
V45  — assessment.assessment_attempts
V46  — assessment.assessment_responses
V47  — assessment.grades  (FK: enrollments, assessment_components)

--- record schema (depends on enrollments, academic_periods, grades) ---
V48  — record.student_academic_records
V49  — record.student_academic_periods
V50  — record.transcripts
V51  — record.graduation_applications

--- finance schema ---
V52  — finance.fee_structures, finance.fee_components
V53  — finance.student_charges
V54  — finance.student_accounts
V55  — finance.payments
V56  — finance.payment_events
V57  — finance.receipts

--- career schema ---
V58  — career.skill_categories, career.skills
V59  — career.student_skills
V60  — career.jobs
V61  — career.job_skills
V62  — career.applications
V63  — career.placement_records

--- risk schema ---
V64  — risk.risk_scores
V65  — risk.risk_factors
V66  — risk.recommendations
V67  — risk.interventions, risk.intervention_actions

--- notification schema ---
V68  — notification.notifications
V69  — notification.notification_preferences
V70  — notification.delivery_attempts

--- indexes/constraints pass ---
V71  — cross-cutting index review: confirm every index named in ERD.md/erd/
       actually exists (a dedicated migration so the index list is
       reviewable as its own change, not buried inside 60+ table migrations)
```

## Reconciliation: 71 planned steps vs. 50 actual physical files (Sprint 5A Day 2)

This document originally planned 71 logical migration steps. The actual
implementation (`infra/db/migrations/V1`–`V50`) has 50 physical files. Per
the Day 2 verification instruction, this is **not** a silently-unreconciled
discrepancy — it breaks down into three distinct, disclosed causes:

1. **Intentional consolidation (the large majority of the gap).** Tables in
   the same schema with no cross-table ordering hazard between them were
   merged into one migration file instead of one file per table, once actual
   DDL made clear the split added no real review value. Full mapping:

   | Planned steps | Actual file | Tables |
   |---|---|---|
   | V1–V36 | V1–V36 (1:1, unchanged) | init → attendance_records |
   | V37, V38 | **V37** `learning_modules_lessons_resources` | course_modules, lessons, lesson_resources |
   | V39, V40, V41 | **V38** `learning_progress_assignments` | learning_progress, assignments, assignment_submissions |
   | V42, V43, V44 | **V39** `assessment_definitions` | assessments, assessment_components, questions |
   | V45, V46 | **V40** `assessment_attempts_responses` | assessment_attempts, assessment_responses |
   | V47 | **V41** `assessment_grades` | grades |
   | V48, V49, V50, V51 | **V42** `record_academic_history` | student_academic_records, student_academic_periods, transcripts, graduation_applications |
   | V52, V53, V54 | **V43** `finance_fees_charges` | fee_structures, fee_components, student_charges, student_accounts |
   | V55, V56, V57 | **V44** `finance_payments` | payments, payment_events, receipts |
   | V58, V59 | **V45** `career_skills` | skill_categories, skills, student_skills |
   | V60, V61, V62, V63 | **V46** `career_jobs_applications` | jobs, job_skills, applications, placement_records |
   | V64, V65 | **V47** `risk_scores_factors` | risk_scores, risk_factors |
   | V66, V67 | **V48** `risk_recommendations_interventions` | recommendations, interventions, intervention_actions |
   | V68, V69, V70 | **V49** `notification_tables` | notifications, notification_preferences, delivery_attempts |

   Verified directly against the physical files' `CREATE TABLE` statements
   (not assumed) during Day 2 reconciliation — every table named in the
   consolidated plan rows above is confirmed present in the corresponding
   actual file, in the same schema, with no table dropped or added.

2. **One planned step dropped, not consolidated: V71 (the standalone
   "index review" migration).** The plan called for a dedicated final pass
   confirming every ERD-named index exists. In the actual implementation,
   indexes and named constraints were instead created inline within each
   table's own `CREATE TABLE` migration (e.g. `uq_enrollments_section_student`
   in `V32`, the `EXCLUDE USING gist` in `V29`) rather than deferred to a
   separate pass. Functionally equivalent coverage — Day 2 verification
   counted 186 indexes and 45 named unique constraints across the 16 domain
   schemas directly from the live database — but the document is corrected
   here rather than left claiming a migration that doesn't exist.

3. **One physical file added that was never in the 71-step plan: `V50`
   (`spring_modulith_event_publication`).** This is Spring Modulith's own
   infrastructure table (`event_publication`, in `public` schema) for its
   JPA event-publication registry — the actual mechanism behind the
   transactional-outbox "commit-before-publish" guarantee already documented
   in the TRD and Kafka catalog. It was not anticipated in the original
   domain-table-driven plan because it isn't a domain table; it surfaced only
   once Hibernate's schema validation ran against a live database during Day
   2 (`SchemaManagementException: missing table [event_publication]`), and
   its field list was taken from decompiling the actual Modulith 2.1.1
   `DefaultJpaEventPublication` entity rather than guessed.

**Net result:** 71 planned − 21 consolidated-away + 0 net change from the
V71 drop (its coverage moved inline, not lost) + 1 added (V50) = 50 actual
files, covering the same 83 domain tables as originally planned, plus the
1 framework table the plan didn't (and couldn't have) anticipated. No table
was silently dropped, renamed, or moved to a different schema in the
consolidation.

## Known Sprint 6 extraction hazard: `assignment_submissions.grade_id`

`learning.assignment_submissions.grade_id` references `assessment.grades`,
but the `learning` schema is created (`V38`) before `assessment` exists
(`V41`) — `learning` sits earlier in both the module list and the FK-driven
migration order. The column is created **without** a foreign key in `V38`,
and the constraint is added afterward via `ALTER TABLE` in `V41`
(`fk_assignment_submissions_grade`), once `assessment.grades` exists. Live
verification (Day 2) confirms exactly one such constraint exists, correctly
pointed at `assessment.grades`.

This is disclosed here as a **known migration dependency and Sprint 6
extraction hazard**: if `learning` and `assessment` are ever split into
separately-deployed services/databases (both appear on the
[Sprint 6 candidate services](sprint6-candidate-services.md) list), this FK
cannot survive the split as a database-level constraint — it would need to
become an application-level/eventual-consistency check (e.g. validated via
the `GradePublished` event already in the Kafka catalog) before extraction,
not carried forward as-is. No other migration in this plan has a same-shape
deferred cross-schema FK; this is the only instance.

## Why this order, restated

1. **Schemas and extensions first** (V2–V4) — nothing else can land without them.
2. **`identity`/`institution` interleaved**, not strictly "module by module,"
   because `user_roles` genuinely needs `colleges` to exist first — the FK
   graph, not the module list, drives real ordering. This is exactly the kind
   of ordering conflict the instruction asked to be caught explicitly rather
   than discovered when a migration fails at `flyway migrate` time.
3. **`catalog.meeting_patterns` migrates after `enrollment.course_sections`**
   despite being conceptually a `catalog` table — another FK-driven exception
   to schema-grouped ordering, called out explicitly rather than silently
   reordered without comment.
4. **`record` schema migrates after `assessment`** — `grades` must exist
   before `student_academic_records` can reference the outcome it's
   summarizing, even though `record` doesn't hold an FK to `grades` directly
   (it's event-driven, not FK-driven, per the Kafka catalog) — the
   *application* dependency still argues for this ordering so integration
   tests can exercise the full `GradePublished → student_academic_records`
   flow without a chicken-and-egg schema gap.
