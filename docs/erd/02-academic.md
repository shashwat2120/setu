# ERD — Tenancy, Academic, Operations

Part of [../ERD.md](../ERD.md) — global conventions (PK/audit/versioning/money/
timestamp defaults) apply unless stated otherwise here.

## Module: institution

### `colleges`

- **Purpose**: the tenant root. `colleges.id` **is** the `tenant_id` referenced
  everywhere else — this table does not itself carry a `tenant_id` column
  (see [contradiction audit #1](../contradiction-audit.md#1-does-colleges-carry-its-own-tenant_id)).
- **PK**: `id UUID`
- **Columns**: `code TEXT NOT NULL`, `name TEXT NOT NULL`, `status TEXT NOT
  NULL DEFAULT 'ACTIVE'`, `timezone TEXT NOT NULL`, `locale TEXT NOT NULL
  DEFAULT 'en-IN'`, `settings_json JSONB NOT NULL DEFAULT '{}'`
- **Unique**: `code`
- **Check**: `status IN ('ACTIVE','SUSPENDED','ARCHIVED')`
- **Tenant ownership**: this table *is* the tenant boundary
- **Version/locking**: yes
- **Owning module**: `institution`
- **Query pattern**: resolved once per request from the security context and
  cached — never queried per-row-of-data

### `campuses`, `academic_organizations`, `departments`

| Table | PK | FK | Key columns | Unique | Notes |
|---|---|---|---|---|---|
| `campuses` | `id` | `tenant_id NOT NULL`, `college_id → colleges(id) NOT NULL` (redundant with `tenant_id` intentionally — see below) | `code, name, address_json` | `(tenant_id, code)` | `college_id` and `tenant_id` are always equal by construction; kept as two columns only because `college_id` reads better in joins — enforce equality with a `CHECK (college_id = tenant_id)` rather than dropping one, to keep the FK self-documenting |
| `academic_organizations` | `id` | `tenant_id NOT NULL`, `campus_id → campuses(id) NOT NULL` | `name, type` | none beyond FK | `type` e.g. `SCHOOL`, `FACULTY`, `INSTITUTE` — free enough to vary by institution shape |
| `departments` | `id` | `tenant_id NOT NULL`, `organization_id → academic_organizations(id) NOT NULL`, `hod_user_id → users(id) NULL` | `code, name` | `(tenant_id, code)` | `hod_user_id` references `users` not `faculty_profiles` — validated by the `identity`/`directory` modules at write time (cross-module: `institution` doesn't own faculty data, so this FK crosses a module boundary deliberately as an **opaque reference**, resolved via application call not a DB join — canonical spec §4 "module boundary rule") |

All three: `version` yes, audit columns yes, owning module `institution`.

---

## Module: catalog

### `academic_years` / `academic_periods`

| Table | PK | FK | Key columns | Constraints |
|---|---|---|---|---|
| `academic_years` | `id` | `tenant_id NOT NULL` | `code, start_date, end_date, status` | `(tenant_id, code)` unique; `CHECK (end_date > start_date)`; **at most one row per tenant with `status = 'CURRENT'`** — enforced via a partial unique index `(tenant_id) WHERE status = 'CURRENT'` |
| `academic_periods` | `id` | `tenant_id NOT NULL`, `academic_year_id → academic_years(id) NOT NULL` | `code, name, sequence, start_date, end_date, registration_open_at, registration_close_at, status` | `(academic_year_id, code)` unique; `CHECK (end_date > start_date)`; `CHECK (registration_close_at > registration_open_at)` |

`status` on `academic_periods` drives the enrollment invariant (registration
open/closed) — see [state-machines.md](../state-machines.md).

### `programs` → `curricula` → `curriculum_requirements` → `curriculum_requirement_courses`

- **`programs`**: `id, tenant_id, department_id → departments NOT NULL, code,
  name, degree_type, duration_terms INT, credits_required NUMERIC(6,2), status`.
  Composite unique `(tenant_id, code)`.
- **`curricula`**: `id, tenant_id, program_id → programs NOT NULL, name,
  version_label, effective_from DATE NOT NULL, effective_to DATE NULL`. A
  program has multiple curriculum *versions* over time — `student_programs`
  (below) pins each student to the curriculum version they're actually held to,
  which is what makes "policy changed mid-program" not retroactively break
  earlier cohorts. Check: `effective_to IS NULL OR effective_to > effective_from`.
- **`curriculum_requirements`**: `id, curriculum_id → curricula NOT NULL,
  group_code TEXT NOT NULL, min_credits NUMERIC(6,2) NULL, min_courses INT
  NULL, rule_json JSONB NOT NULL DEFAULT '{}'`. At least one of `min_credits`/
  `min_courses` should be set — **not enforced at the DB level** (a `CHECK`
  can't easily express "at least one of two nullable columns"; enforce in the
  application's curriculum-authoring validation instead, flagged so it isn't
  silently dropped).
- **`curriculum_requirement_courses`**: `id, tenant_id NOT NULL, requirement_id →
  curriculum_requirements NOT NULL, course_id → courses NOT NULL, requirement_role
  TEXT NOT NULL` (`CORE`/`ELECTIVE`/`SUBSTITUTE`). Composite unique
  `(requirement_id, course_id)`. **`tenant_id` added per
  [erd-audit-2.md finding A](../erd-audit-2.md#a-missing-tenant-ownership--real-idor-risk-not-style)**
  — this joins two independently tenant-owned entities; the application
  service must verify both sides agree with the join row's `tenant_id` before insert.

Owning module: `catalog` for all four. `version` yes on `programs`/`curricula`;
`curriculum_requirements`/`curriculum_requirement_courses` are effectively
child rows of a curriculum edit, versioned at the parent.

### `courses` and `course_prerequisites`

- **`courses`**: `id, tenant_id, department_id → departments NOT NULL, code,
  title, credits NUMERIC(4,1) NOT NULL, course_type TEXT, level TEXT,
  description TEXT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE'`. Composite
  unique `(tenant_id, code)`. This is the **catalog** definition — never
  scheduled or capacity-bound itself (canonical spec §3 "course vs section").
- **`course_prerequisites`**: `id, tenant_id, course_id → courses NOT NULL,
  prerequisite_course_id → courses NOT NULL, rule_type TEXT NOT NULL
  ('REQUIRED'|'RECOMMENDED'|'COREQUISITE'), minimum_grade TEXT NULL`.
  - **Check**: `course_id <> prerequisite_course_id` (no self-loop at the row level).
  - **Cycle prevention**: a `CHECK` constraint cannot express graph-cycle
    freedom. This **must** be enforced at write time in the `catalog` module's
    application service — before insert, walk the prerequisite graph from
    `prerequisite_course_id` and reject if `course_id` is reachable. Flagged
    explicitly per the user's "be especially critical" list — a naive
    trigger-per-row won't catch a cycle introduced by two separate inserts
    each individually valid; the check must walk the *whole* graph transactionally.
  - **Index**: `(course_id)`, `(prerequisite_course_id)` — both directions are
    queried (forward for "what do I need", reverse for "what depends on this
    course" before deprecating a course)

### `buildings`, `rooms`, `meeting_patterns`

| Table | Key columns | Constraints |
|---|---|---|
| `buildings` | `id, tenant_id, code, name` | `(tenant_id, code)` unique |
| `rooms` | `id, tenant_id, building_id → buildings NOT NULL, code, capacity INT NOT NULL, features_json JSONB` | `(building_id, code)` unique; `CHECK (capacity > 0)` |
| `meeting_patterns` | `id, tenant_id, section_id → course_sections NOT NULL, day_of_week SMALLINT NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL, room_id → rooms NOT NULL, effective_from DATE, effective_to DATE` | `CHECK (day_of_week BETWEEN 0 AND 6)`, `CHECK (end_time > start_time)`. **No DB-level exclusion constraint is specified here** — the real requirement is "prevent overlapping meetings for the same room or faculty," which Postgres *can* enforce natively with a `tstzrange` + `EXCLUDE USING gist` constraint. This is the safest resolution (DB-enforced, not just app-checked) and should be the actual migration; noted as a required `EXCLUDE` constraint on `(room_id, day_of_week) WITH =, meeting_time_range WITH &&` once the migration is written. |

Owning module: `catalog` (rooms/buildings are catalog-adjacent scheduling
resources, not their own module — not worth the extraction overhead per
non-negotiable "never one service per table").

---

## Module: enrollment (Operations: classes → enrollment → timetable)

### `classes`, `class_students`, `class_faculty`

- **`classes`**: `id, tenant_id, program_id → programs NOT NULL, cohort_code
  TEXT NOT NULL, intake_year INT NOT NULL, name TEXT, status TEXT NOT NULL
  DEFAULT 'ACTIVE'`. This is **cohort/group identity** — not a scheduled course
  offering (canonical spec §3 "class vs section"). Composite unique
  `(tenant_id, program_id, cohort_code, intake_year)`.
- **`class_students`**: `id, tenant_id NOT NULL, class_id → classes NOT NULL,
  student_id → student_profiles NOT NULL, starts_at DATE NOT NULL, ends_at
  DATE NULL, status TEXT NOT NULL DEFAULT 'ACTIVE'`. **Unique active
  membership**: partial unique `(class_id, student_id) WHERE status =
  'ACTIVE'` — a student can have historical (ended) memberships in multiple
  classes but only one active one per class row (typically one active cohort
  membership overall, though the constraint is scoped per-class to allow the
  same student appearing in two different `classes` rows only if one is
  `ENDED`). `tenant_id` added per erd-audit-2.md finding A.
- **`class_faculty`**: `id, tenant_id NOT NULL, class_id → classes NOT NULL,
  faculty_id → faculty_profiles NOT NULL, role TEXT NOT NULL
  ('ADVISOR'|'COORDINATOR'), starts_at DATE, ends_at DATE`. `tenant_id` added
  per erd-audit-2.md finding A. Distinct from `faculty_assignments` (below):
  this is **cohort-level advising**, that is **section-level teaching** —
  verified not duplicate, see erd-audit-2.md finding F.

### `course_sections` and `faculty_assignments`

- **`course_sections`**: `id, tenant_id, course_id → courses NOT NULL,
  period_id → academic_periods NOT NULL, class_id → classes NULL, section_code
  TEXT NOT NULL, capacity INT NOT NULL, room_id → rooms NULL, status TEXT NOT
  NULL DEFAULT 'DRAFT', registration_open_at TIMESTAMPTZ, registration_close_at
  TIMESTAMPTZ`.
  - **`class_id` is nullable** — resolved ambiguity, see
    [audit #7](../contradiction-audit.md#7-course_sectionclass_id-nullability-mandatory-cohort-vs-open-elective).
    A section tied to a cohort (`class_id` set) is a mandatory, cohort-scheduled
    offering; a section with `class_id = NULL` is an open elective any eligible
    student across the tenant can enroll in.
  - Composite unique `(tenant_id, course_id, period_id, section_code)`.
  - `CHECK (capacity > 0)`.
  - `status IN ('DRAFT','OPEN','CLOSED','CANCELLED')` — see state machine.
- **`faculty_assignments`**: `id, tenant_id, section_id → course_sections NOT
  NULL, faculty_id → faculty_profiles NOT NULL, role TEXT NOT NULL
  ('PRIMARY'|'CO_INSTRUCTOR'|'TA'), allocation_pct NUMERIC(5,2) NULL,
  starts_at DATE, ends_at DATE`. **Unique active primary**: partial unique
  `(section_id) WHERE role = 'PRIMARY' AND ends_at IS NULL` — a section has at
  most one *current* primary instructor, but historical primary-instructor
  rows (with `ends_at` set) are kept, not deleted. **Index**: `(faculty_id)
  WHERE ends_at IS NULL` — "what am I teaching" is a hot faculty-dashboard
  query, missing from the original ERD pass (caught in the final consistency
  audit's index review, section O).

### `student_programs`

- **`student_programs`**: `id, tenant_id, student_id → student_profiles NOT
  NULL, program_id → programs NOT NULL, curriculum_id → curricula NOT NULL,
  admission_year INT NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE',
  is_primary BOOLEAN NOT NULL DEFAULT true, starts_at DATE NOT NULL, ends_at
  DATE NULL`.
  - **Policy gap resolved** (was undefined — see
    [audit #8](../contradiction-audit.md#8-student_programs-single-vs-dual-program-policy)):
    default is **one active primary program per student**, enforced by a
    partial unique index `(tenant_id, student_id) WHERE status = 'ACTIVE' AND
    is_primary = true`. A tenant-level setting `colleges.settings_json ->>
    'allow_dual_program'` may permit *additional* `is_primary = false` active
    programs — those are unconstrained by the partial index, so dual-program
    support doesn't require a schema change later, only a policy toggle.
  - History preserved: rows are never deleted, only `status`/`ends_at` set.

`student_academic_periods` moved to the Record module —
[erd/03-learning-assessment-records.md](03-learning-assessment-records.md) —
per your documentation clarification: it represents the student's official
term snapshot, so it's Record-owned in Sprint 5 even though it's created in
reaction to this module's `academic_periods`.

### `enrollments`, `enrollment_actions`, `enrollment_overrides`, `waitlist_entries`

- **`enrollments`**: `id, tenant_id, section_id → course_sections NOT NULL,
  student_id → student_profiles NOT NULL, status TEXT NOT NULL DEFAULT
  'REQUESTED', requested_at TIMESTAMPTZ NOT NULL DEFAULT now(), enrolled_at
  TIMESTAMPTZ NULL, dropped_at TIMESTAMPTZ NULL, final_status TEXT NULL`.
  - **Composite unique**: `(section_id, student_id)` — one enrollment row per
    student per section, ever (state machine transitions happen on this one
    row rather than inserting a new row per attempt, so re-enrollment after a
    drop reuses/reopens the same row — this is deliberate: it keeps the full
    history on one traceable id instead of scattering it).
  - **Version/locking**: yes, and this is the **capacity-race** table — see
    [transaction-concurrency-matrix.md](../transaction-concurrency-matrix.md)
    for the exact locking strategy (short answer: `SELECT ... FOR UPDATE` on
    the `course_sections` row to serialize capacity checks, not on `enrollments`
    itself, since the race is over the *section's remaining capacity*, a
    derived count, not any single enrollment row).
  - **Index**: `(section_id, status)` — capacity counting query; `(student_id,
    status)` — "my enrollments" query.
- **`enrollment_actions`**: `id, enrollment_id → enrollments NOT NULL, action
  TEXT NOT NULL, actor_user_id → users NOT NULL, reason TEXT NULL,
  metadata_json JSONB, created_at`. **Append-only audit** — no update, no
  delete, ever. Index `(enrollment_id, created_at)`.
- **`enrollment_overrides`**: `id, tenant_id, student_id → student_profiles NOT
  NULL, section_id → course_sections NOT NULL, type TEXT NOT NULL
  ('PREREQUISITE'|'CAPACITY'|'CREDIT_LIMIT'|'HOLD'), reason TEXT NOT NULL,
  approved_by → users NOT NULL, expires_at TIMESTAMPTZ NULL`. Always an
  **explicit, approved exception** — never a silent bypass (canonical spec §3).
  Application logic checks for an active override of the matching `type`
  *before* rejecting an enrollment attempt, and records which override was
  used in the resulting `enrollment_actions` row.
- **`waitlist_entries`**: `id, tenant_id, section_id → course_sections NOT
  NULL, student_id → student_profiles NOT NULL, position INT NOT NULL, status
  TEXT NOT NULL DEFAULT 'WAITING', created_at TIMESTAMPTZ NOT NULL DEFAULT
  now()`. Composite unique `(section_id, student_id)`. **Position management is
  transactional**: promoting the next waitlisted student when a seat frees up
  must happen in the same transaction as the drop that freed it, with `SELECT
  ... FOR UPDATE` ordered by `position`, to avoid two concurrent drops both
  promoting the same "next" student.

**Enrollment invariant** (canonical spec §5, restated as the exact check order
the application enforces, transactionally, before commit):

```
authenticated student -> active program (student_programs) -> period open
(academic_periods.status) -> course offered in this section (course_sections)
-> prerequisite satisfied (course_prerequisites, walked against
student_academic_records) -> no duplicate enrollment (unique constraint)
-> no schedule conflict (meeting_patterns overlap check against student's
other active enrollments in the same period) -> capacity available
(SELECT ... FOR UPDATE on course_sections) OR waitlist -> credit limit valid
(sum of credits across active enrollments in period vs program policy)
-> no blocking hold -> optional override (enrollment_overrides) -> COMMIT
```

Transaction boundary for the whole chain: **one database transaction**, owned
by `enrollment`, `SELECT ... FOR UPDATE` on the target `course_sections` row to
serialize capacity decisions (see transaction-concurrency-matrix.md).
