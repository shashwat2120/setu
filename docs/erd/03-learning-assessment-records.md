# ERD — Attendance, LMS, Assessment, Record

Part of [../ERD.md](../ERD.md) — global conventions apply unless stated otherwise.

## Module: attendance

### `attendance_sessions`

- **Purpose**: a faculty-opened, short-lived attendance window for one class meeting
- **PK**: `id UUID`
- **FK**: `tenant_id NOT NULL`, `section_id → course_sections NOT NULL`,
  `meeting_id → meeting_patterns NOT NULL`, `opened_by → users NOT NULL`
- **Columns**: `started_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `ends_at
  TIMESTAMPTZ NOT NULL`, `token_hash TEXT NOT NULL`, `status TEXT NOT NULL
  DEFAULT 'OPEN'`
- **Check**: `status IN ('OPEN','CLOSED')`; `ends_at > started_at`
- **`token_hash` clarification** (resolves apparent tension in source material
  — see [audit #9](../contradiction-audit.md#9-attendance-token_hash-vs-never-persist-raw-token)):
  this is a hash of the **current rotating** token, refreshed on every rotation
  in `attendance_qr_sessions` — the raw token is never written to either table,
  only ever transmitted to the display and validated in-memory/Redis against
  the hash.
- **One active session per meeting**: partial unique index `(meeting_id)
  WHERE status = 'OPEN'`
- **Version/locking**: yes
- **Transaction boundary / owning module**: `attendance`

### `attendance_qr_sessions`

- **PK**: `id UUID`
- **FK**: `attendance_session_id → attendance_sessions NOT NULL`
- **Columns**: `nonce TEXT NOT NULL`, `expires_at TIMESTAMPTZ NOT NULL`,
  `rotation_no INT NOT NULL DEFAULT 0`
- **Indexes**: `(attendance_session_id, rotation_no)` descending — fetch latest
  rotation fast. Short-lived rows; a cleanup job or TTL-equivalent policy
  should prune rows once the parent session closes (not required to persist
  indefinitely — these are ephemeral, candidates for Redis instead of Postgres
  in practice, but modeled here since the canonical spec lists them as a table).
- **Transaction boundary / owning module**: `attendance`

### `faculty_attendance`

- **Purpose**: a faculty member's **own** daily attendance (staff check-in / check-out), separate from `attendance_records`,
  which records *students* in a class meeting. One row per faculty member per calendar day.
- **PK**: `id UUID`
- **FK**: `tenant_id → colleges NOT NULL`, `faculty_id → faculty_profiles NOT NULL`, `corrected_by → users NULL`
- **Columns**: `attendance_date DATE NOT NULL` (the day in the **college's own timezone**, not UTC), `status TEXT NOT NULL`
  (`PRESENT`/`HALF_DAY`/`ABSENT`/`ON_LEAVE`), `check_in_at TIMESTAMPTZ NULL`, `check_out_at TIMESTAMPTZ NULL`,
  `source TEXT NOT NULL` (`SELF`/`ADMIN`), `correction_reason TEXT NULL`, `created_at`, `updated_at`, `version INT NOT NULL DEFAULT 0`
- **Composite unique**: `(faculty_id, attendance_date)` — the one-record-per-day guard, DB-enforced so two concurrent check-ins
  cannot both insert
- **Checks**: `check_out_at IS NULL OR check_in_at IS NOT NULL`; `check_out_at >= check_in_at`; `ABSENT`/`ON_LEAVE` rows carry no
  check-in/out times; a row with `source = 'ADMIN'` must have `corrected_by` and `correction_reason`
- **Indexes**: `(tenant_id, attendance_date)` for the admin day view; `(faculty_id, attendance_date DESC)` for own history
- **Version/locking**: yes — a faculty check-out and an admin correction can race on the same row
- **Policy-derived metrics** (V75): `late_minutes INT NULL` (minutes after shift start, only when beyond the grace period),
  `worked_minutes INT NULL` (check-out minus check-in), `policy_version INT NULL` (the policy version used), `short_hours BOOLEAN NOT NULL
  DEFAULT FALSE` (worked less than the half-day minimum). NULL on rows evaluated before the policy existed, and always NULL on
  `ABSENT`/`ON_LEAVE`. There is no `LATE` *status*: late is `late_minutes > 0` on a `PRESENT`/`HALF_DAY` day. `HALF_DAY` is set by an
  administrator **or** automatically at self check-out when fewer than the policy's full-day minutes were worked
- **Owning module**: `attendance`

### `staff_attendance_policies`

- **Purpose**: a college's rules for judging faculty (staff) attendance — shift, grace period and the hours that make a full or half
  day. **Append-only versions**: changing the rules inserts a new row; old rows are never edited, so every attendance record can cite
  the exact policy version it was judged under and past days are never silently re-judged.
- **PK**: `id UUID`
- **FK**: `tenant_id → colleges NOT NULL`, `created_by → users NOT NULL`
- **Columns**: `version INT NOT NULL` (1, 2, … per college), `shift_start_minute INT`, `shift_end_minute INT` (college-local wall-clock times as
  minutes since midnight, 0-1439 — an integer, not a `TIME`, so a non-UTC server's JDBC time-zone handling can never shift them),
  `grace_minutes INT`, `full_day_minutes INT`, `half_day_minutes INT`, `created_at`
- **Composite unique**: `(tenant_id, version)` — two administrators saving at once cannot both take the same version
- **Checks**: `shift_start_minute < shift_end_minute`; `0 <= grace_minutes <= 120`; `0 < half_day_minutes < full_day_minutes <= 1440`
- **Current policy** = the highest `version` for the college. A college with no row uses the built-in defaults (**version 0**,
  not stored): shift 09:00–17:00, 15 minutes' grace, 480 minutes for a full day, 240 for a half day.
- **Owning module**: `attendance`

### `faculty_attendance_regularisations`

- **Purpose**: a faculty member's request to correct a past day of their OWN attendance (for example a forgotten check-in or
  check-out), decided by an administrator. Approval applies the correction to `faculty_attendance`; the request itself is the
  permanent record of who asked, why, and who decided.
- **PK**: `id UUID`
- **FK**: `tenant_id → colleges NOT NULL`, `faculty_id → faculty_profiles NOT NULL`, `decided_by → users NULL`
- **Columns**: `attendance_date DATE NOT NULL` (college timezone, strictly before today), `requested_status TEXT NOT NULL`
  (`PRESENT`/`HALF_DAY`/`ON_LEAVE`), `requested_check_in_at`/`requested_check_out_at TIMESTAMPTZ NULL`, `reason TEXT NOT NULL`,
  `status TEXT NOT NULL DEFAULT 'PENDING'` (`PENDING`/`APPROVED`/`REJECTED`/`WITHDRAWN`), `decided_at TIMESTAMPTZ NULL`,
  `decision_note TEXT NULL`, `created_at`, `updated_at`, `version INT NOT NULL DEFAULT 0`
- **Partial unique**: `(faculty_id, attendance_date) WHERE status = 'PENDING'` — at most one open request per faculty member per day,
  DB-enforced
- **Checks**: times only on `PRESENT`/`HALF_DAY`; `requested_check_out_at > requested_check_in_at`; a check-out needs a check-in;
  `decided_by`/`decided_at` are set exactly when status is `APPROVED` or `REJECTED`; a rejection carries a `decision_note`
- **Indexes**: `(tenant_id, status, created_at)` for the admin queue; `(faculty_id, created_at DESC)` for a member's own list
- **Version/locking**: yes — two administrators deciding the same request, or the member withdrawing while an admin decides
- **Owning module**: `attendance`

### `attendance_records`

- **Purpose**: the actual per-student check-in — the **only** source for
  attendance percentage (TRD §3: never a stored aggregate column)
- **PK**: `id UUID`
- **FK**: `attendance_session_id → attendance_sessions NOT NULL`, `enrollment_id
  → enrollments NOT NULL`, `corrected_by → users NULL`
- **Columns**: `status TEXT NOT NULL` (`PRESENT`/`LATE`/`ABSENT`/`EXCUSED`),
  `marked_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `source TEXT NOT NULL`
  (`QR_SCAN`/`MANUAL`/`IMPORT`), `version INT NOT NULL DEFAULT 0`
- **Version/locking**: yes — added per
  [transaction-concurrency-matrix.md scenario 4](../transaction-concurrency-matrix.md#4-concurrent-attendance-correction),
  since this row is updatable (an exception to append-only, per the
  correction note below) it needs optimistic locking like any other mutable
  row, which the original ERD pass omitted
- **Composite unique**: `(attendance_session_id, enrollment_id)` — this is the
  **duplicate-scan concurrency guard**: a second scan for the same student in
  the same session hits the unique constraint and is rejected at the DB level,
  not just application logic (belt-and-suspenders against a race between two
  near-simultaneous scan requests)
- **Correction**: a correction **updates this row** (unlike grades, which are
  append-only) but **requires** `corrected_by` to be set and an
  `enrollment_actions`-style audit row written in the same transaction — the
  row itself isn't append-only, but every correction is externally audited.
  This is a deliberate, narrower exception to the "historical data is
  append-only" convention, justified because attendance correction is a
  single-fact fix (present vs. absent), not a multi-step re-evaluation like
  grading.
- **Transaction boundary**: the check-in write + outbox `AttendanceRecorded`
  event commit atomically (canonical spec §3 "Events")
- **Owning module**: `attendance`
- **High-volume query pattern**: `COUNT(*) WHERE enrollment_id = ? AND status
  IN ('PRESENT','LATE')` vs `COUNT(*) WHERE enrollment_id = ?` — the attendance
  percentage query, needs `(enrollment_id, status)` index; runs on every
  student/faculty dashboard load, a strong candidate for a Redis-cached,
  event-invalidated read model per TRD §3's "at most a materialized/cached
  read model that is invalidated on every write."

---

## Module: learning (LMS)

| Table | PK | FK | Key columns | Constraints |
|---|---|---|---|---|
| `course_modules` | `id` | `tenant_id`, `section_id → course_sections NOT NULL` | `title, sequence INT, status` | `(section_id, sequence)` unique |
| `lessons` | `id` | `module_id → course_modules NOT NULL` | `title, type, content_ref TEXT, sequence INT, duration_minutes INT` | `(module_id, sequence)` unique; `content_ref` must be `https://…` (e.g. a YouTube video), `ref://…` or `s3://…` — enforced by `ContentReferences` |
| `lesson_resources` | `id` | `lesson_id → lessons NOT NULL` | `media_ref TEXT NOT NULL, type TEXT NOT NULL, title TEXT NULL` | binary media **outside Postgres** — `media_ref` is an object-storage key/URL, never bytes in this table (canonical spec §15). `title` (V76) is an optional display name, 1–200 chars. `media_ref` accepts only `https://` (host required, no credentials, no whitespace, ≤2048 chars), `ref://` or `s3://`; `javascript:`, `data:`, `file:` and plain `http://` are refused at write time. A YouTube `content_ref` is shown to students as an embedded player (privacy-enhanced domain) with a plain link beneath it. |
| `learning_progress` | `id` | `lesson_id → lessons NOT NULL`, `student_id → student_profiles NOT NULL` | `state TEXT, percent NUMERIC(5,2), started_at, completed_at, last_position JSONB` | `(lesson_id, student_id)` unique; `CHECK (percent BETWEEN 0 AND 100)`; progress is **evidence, not a grade** unless a tenant explicitly configures a module to count it as one (application-level policy, not a schema flag) |
| `assignments` | `id` | `tenant_id`, `section_id → course_sections NOT NULL` | `title, max_marks NUMERIC(6,2), due_at TIMESTAMPTZ, late_policy JSONB, status` | ownership flows through `section_id`, not a direct module link |
| `assignment_submissions` | `id` | `assignment_id → assignments NOT NULL`, `student_id → student_profiles NOT NULL`, `grade_id → grades NULL` | `attempt_no INT NOT NULL DEFAULT 1, submitted_at, status, content_ref TEXT` | `(assignment_id, student_id, attempt_no)` unique |

Access to all of the above is limited to enrolled students / authorized
faculty for the owning `section_id` — enforced by the standard authorization
pipeline (canonical spec §6), not a separate ACL table. Owning module:
`learning` for everything except `assignments`/`assignment_submissions`, which
sit in `learning` too (assignments are an LMS concept feeding into, but not
owned by, `assessment`).

---

## Module: assessment

### `assessments`, `assessment_components`, `questions`

- **`assessments`**: `id, tenant_id, section_id → course_sections NOT NULL,
  type TEXT, title TEXT NOT NULL, total_marks NUMERIC(6,2), duration_minutes
  INT, starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ, status TEXT NOT NULL
  DEFAULT 'DRAFT'`. **Published state is immutable enough for reproducibility**
  — once `status = 'PUBLISHED'`, `total_marks`/`starts_at`/`ends_at` cannot be
  edited in place; a correction requires a new version (mirrors the grade
  versioning pattern) so that a student's completed attempt is always
  evaluated against the exact assessment definition it was taken under.
- **`assessment_components`**: `id, assessment_id → assessments NOT NULL, code
  TEXT NOT NULL, max_marks NUMERIC(6,2) NOT NULL, weight NUMERIC(5,2) NOT
  NULL, order_no INT`. `(assessment_id, code)` unique. **Sum/weight validated
  at publish time** — application check, not a DB constraint (weights across
  components must sum to 100, or to the assessment's declared total — enforced
  in the publish-transition handler, part of the assessment state machine).
- **`questions`**: `id, component_id → assessment_components NOT NULL, type
  TEXT, prompt TEXT NOT NULL, points NUMERIC(5,2) NOT NULL, difficulty TEXT
  NULL, skill_id UUID NULL, metadata_json JSONB`. Item **versioning
  recommended** — if a question is edited after any student has attempted it,
  create a new question row and retire the old one (referenced by historical
  `assessment_responses`) rather than mutating in place, for the same
  reproducibility reason as assessments.

### `assessment_attempts`, `assessment_responses`

- **`assessment_attempts`**: `id, assessment_id → assessments NOT NULL,
  student_id → student_profiles NOT NULL, attempt_no INT NOT NULL DEFAULT 1,
  started_at TIMESTAMPTZ, submitted_at TIMESTAMPTZ NULL, status TEXT NOT NULL
  DEFAULT 'IN_PROGRESS', score NUMERIC(6,2) NULL`. `(assessment_id, student_id,
  attempt_no)` unique.
- **`assessment_responses`**: `id, attempt_id → assessment_attempts NOT NULL,
  question_id → questions NOT NULL, answer_json JSONB, is_correct BOOLEAN
  NULL, points_awarded NUMERIC(5,2) NULL`. `(attempt_id, question_id)` unique.
- **Attempt score vs. published grade — resolved relationship** (was
  ambiguous, see [audit #10](../contradiction-audit.md#10-assessment_attemptsscore-vs-gradesraw_marks-relationship)):
  `assessment_attempts.score` is the **provisional, attempt-level** computed/
  auto-graded result — it belongs to the attempt, not to the official record.
  A separate **evaluation step** (which may include manual moderation, e.g. an
  instructor adjusting an auto-grader's score) produces the row in `grades`
  for the corresponding `assessment_component`. `grades.raw_marks` is the
  **published, official** figure; `assessment_attempts.score` can legally
  differ from it until evaluation completes, and remains as the historical
  record of what the auto-grader originally computed.

---

## Module: assessment / record — grades and the academic record

### `grades`

- **Purpose**: the authoritative, versioned mark for one (enrollment,
  component) pair
- **PK**: `id UUID`
- **FK**: `tenant_id NOT NULL`, `enrollment_id → enrollments NOT NULL`,
  `component_id → assessment_components NOT NULL`, `published_by → users NULL`
- **Columns**: `raw_marks NUMERIC(6,2) NOT NULL`, `grade_code TEXT NULL`,
  `grade_point NUMERIC(3,2) NULL`, `status TEXT NOT NULL DEFAULT 'DRAFT'`,
  `published_at TIMESTAMPTZ NULL`, `version INT NOT NULL DEFAULT 1`,
  `supersedes_id → grades(id) NULL`
- **Append-only**: **do not `UPDATE` a `PUBLISHED` row.** A correction inserts
  a new row with `version = previous.version + 1` and `supersedes_id =
  previous.id`; the "current" grade for a pair is resolved by `ORDER BY
  version DESC LIMIT 1` (or a maintained `is_current` flag kept in sync by the
  same transaction that inserts the correction — pick one and be consistent;
  recommend the flag for query simplicity, updated in the same transaction as
  the insert, never as a separate step that could be missed).
- **Index**: `(enrollment_id, component_id, is_current) WHERE is_current = true`
  — the hot "get current grade" path.
- **Transaction boundary**: publish/correct + outbox `GradePublished` event,
  atomic.
- **Owning module**: `assessment`

### `student_academic_records`, `transcripts`, `graduation_applications` — module `record`

Per **approved decision H3**: Assessment produces academic outcomes
(`GradePublished`); **Record owns the student's authoritative academic
history**, a distinct business responsibility, consuming that event rather
than being folded into `assessment`. `record` stays its own strict Sprint 5
module and is a genuine Sprint 6 extraction **candidate** (not forced into
Assessment) — validated at Day 16 like every other candidate.

| Table | Key columns | Notes |
|---|---|---|
| `student_academic_records` | `id, tenant_id, student_id, period_id, enrollment_id, credits_attempted, credits_earned, grade_point, quality_points` | Append/preserve official history — same "never delete, never silently update a posted row" rule as `grades`. Written by `record` in reaction to `assessment`'s `GradePublished` event (async, outbox-driven), not a synchronous cross-module write from `assessment`. |
| `student_academic_periods` | `id, tenant_id, student_id, period_id, status, standing, term_gpa, credits_attempted, credits_earned, posted_at` | **Record-owned (confirmed)**: the student's official term snapshot — same character as `student_academic_records`, even though it's derived from `academic_periods`/`enrollment` activity. Composite unique `(student_id, period_id)`. **Derived-then-frozen**: `term_gpa`/`credits_attempted`/`credits_earned` recompute live from `student_academic_records` while `posted_at IS NULL`; once posted, frozen — a later correction creates a new correction trail rather than rewriting the posted row, same pattern as `grades`. Sprint 6 service ownership (whether it stays with Academic Record or moves) remains a Day-16 validation question, not decided here. |
| `transcripts` | `id, tenant_id, student_id, generated_at, version INT, document_ref TEXT, status` | Generated **from** `student_academic_records` — never hand-edited; regenerating creates a new versioned row, old ones retained |
| `graduation_applications` | `id, tenant_id, student_id, target_period_id, status, submitted_at, reviewed_at` | **Confirmed under Record**: eligibility/curriculum rules remain owned by `catalog`/Institution-Academic (`degree_progress`, `curriculum_requirements`) — `record` **consumes** the eligibility result via an application call at review time, it does not own or reinterpret curriculum rules itself. This is now the settled division of responsibility, not an open question. |

### `degree_progress` — module `catalog` (moved from `record` per H3)

- **Purpose**: curriculum-driven completion projection — **owned by
  `catalog`/Institution-Academic, not `record` or `assessment`**, per approved
  decision H3: "Degree Progress is curriculum/requirement driven and
  therefore belongs close to Institution/Academic." Reclassified out of the
  Records module table group above; schema/table-ownership maps updated to match.
- **Columns**: `id, tenant_id, student_id, curriculum_id → curricula, credits_required,
  credits_earned, percentage, calculated_at`
- **Derived projection, recomputable** — rebuilt from `record.student_academic_records`
  + `catalog.curriculum_requirements` (a cross-module read via application
  call, consistent with the module-boundary rule). Not itself a source of
  truth; safe to treat as a cache. Candidate for the `analytics` module's
  read-model pattern once Sprint 6 extracts it, regardless of which module
  computes it in Sprint 5.
- **Owning module**: `catalog`

Owning module: `record` for all four.
