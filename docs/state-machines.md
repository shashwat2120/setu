# Setu — State-Machine Catalog

Pre-coding design artifact per [FINAL_CANONICAL_PROJECT_SPEC.md](FINAL_CANONICAL_PROJECT_SPEC.md)
§17.4/§18. Schema this refers to is in [ERD.md](ERD.md) and [erd/](erd/).

Format per entity: states → transitions (who can perform, validation) →
terminal states → correction rules → audit → emitted event.

---

## 1. User

- **States**: `ACTIVE`, `SUSPENDED`, `DEACTIVATED`
- **Transitions**:
  - `ACTIVE → SUSPENDED` — by `COLLEGE_ADMIN`/`SUPER_ADMIN` with a reason;
    invalidates all active sessions immediately (not lazily on next request)
  - `SUSPENDED → ACTIVE` — by `COLLEGE_ADMIN`/`SUPER_ADMIN`
  - `ACTIVE|SUSPENDED → DEACTIVATED` — by `SUPER_ADMIN` only, or by the user
    themself (account closure request); terminal
- **Validation**: cannot suspend/deactivate the last remaining `SUPER_ADMIN`
  on the platform (lockout guard)
- **Terminal**: `DEACTIVATED`
- **Correction/rollback**: none — `DEACTIVATED` is one-way; a "reactivation"
  is a new account, not a state reversal, to keep the audit trail honest
- **Audit**: every transition writes `audit_logs` with actor, reason
- **Emitted event**: `UserSuspended`, `UserDeactivated`

## 2. Passkey

- **States**: `ACTIVE`, `REVOKED`
- **Transitions**: `ACTIVE → REVOKED` — by the account owner, or by an admin
  on suspected compromise
- **Validation**: revoking must not leave the account with zero active
  credentials (at least one passkey or a working password fallback must
  remain) — reject the revoke, or require the caller to set up a replacement
  credential first
- **Terminal**: `REVOKED`
- **Correction**: none — a revoked passkey is never reactivated; the user
  registers a new one
- **Audit**: `audit_logs` on revoke
- **Emitted event**: `PasskeyRevoked`

## 3. Session

- **States**: `ACTIVE` (implicit — no explicit column, derived from
  `revoked_at IS NULL AND expires_at > now()`), `EXPIRED`, `REVOKED`
- **Transitions**:
  - `ACTIVE → EXPIRED` — automatic, time-based, no actor
  - `ACTIVE → REVOKED` — by the user (logout / "sign out other devices"), by
    an admin (forced logout), or automatically on refresh-token-reuse
    detection (a rotated token used a second time revokes the whole token
    family, not just that one session)
- **Validation**: refresh rotation must check `revoked_at IS NULL` before
  issuing a new token; reuse of an already-rotated token revokes the family
- **Terminal**: `EXPIRED`, `REVOKED`
- **Correction**: none — a new session is created, not a revoked one restored
- **Audit**: forced/security revocations logged; routine expiry is not
- **Emitted event**: `SessionRevoked` (security-relevant revocations only —
  not emitted for ordinary time-based expiry)

## 4. College

- **States**: `ACTIVE`, `SUSPENDED`, `ARCHIVED`
- **Transitions**: `ACTIVE → SUSPENDED` (platform-level, `SUPER_ADMIN` only —
  e.g. non-payment, policy violation) → blocks all tenant logins except
  `SUPER_ADMIN`; `SUSPENDED → ACTIVE`; `ACTIVE|SUSPENDED → ARCHIVED` — terminal,
  tenant is read-only from this point
- **Validation**: cannot archive a tenant with any `ACTIVE` `academic_periods`
  mid-term (must wait for period close, or require explicit force-flag with a
  documented reason)
- **Terminal**: `ARCHIVED`
- **Correction**: none for `ARCHIVED`; `SUSPENDED → ACTIVE` is the only reversal path
- **Audit**: `audit_logs`, platform-scoped (`tenant_id` context is the college
  itself)
- **Emitted event**: `CollegeSuspended`, `CollegeArchived`

## 5. Academic Year

- **States**: `PLANNED`, `CURRENT`, `CLOSED`
- **Transitions**: `PLANNED → CURRENT` — by `COLLEGE_ADMIN`; enforces the
  partial-unique "one `CURRENT` year per tenant" invariant, so this transition
  must first move the previously-`CURRENT` year to `CLOSED` in the same
  transaction; `CURRENT → CLOSED` — by `COLLEGE_ADMIN`, requires all child
  `academic_periods` to be `CLOSED` first
- **Validation**: cannot skip `PLANNED → CLOSED` directly
- **Terminal**: `CLOSED`
- **Correction**: none — reopening a closed year is out of scope for v1
  (flagged as a real institutional need that may require a `REOPENED` state
  later, not built now)
- **Audit**: yes
- **Emitted event**: `AcademicYearActivated`, `AcademicYearClosed`

## 6. Academic Period

- **States**: `PLANNED`, `REGISTRATION_OPEN`, `REGISTRATION_CLOSED`, `IN_PROGRESS`, `CLOSED`
- **Transitions**: linear, in the order listed, each by `COLLEGE_ADMIN`, each
  timestamp-gated by `registration_open_at`/`registration_close_at` (the
  transition can happen automatically via a scheduled job at those timestamps,
  or manually early/late by an admin action, which is itself audited as an
  override of the schedule)
- **Validation**: `enrollment` requests are only accepted while status is
  `REGISTRATION_OPEN`; `CLOSED` requires all sections in the period to be
  `CLOSED` or `CANCELLED`
- **Terminal**: `CLOSED`
- **Correction**: manual override of the automatic schedule is allowed
  (audited); no reversal once `CLOSED`
- **Audit**: yes, including manual schedule overrides specifically
- **Emitted event**: `RegistrationOpened`, `RegistrationClosed`, `PeriodClosed`

## 7. Student (student_profiles)

- **States**: `ACTIVE`, `ON_LEAVE`, `SUSPENDED`, `GRADUATED`, `WITHDRAWN`
- **Transitions**: `(none) → ACTIVE` — **creation/admission**, added in the
  Kafka reconciliation pass: a `COLLEGE_ADMIN` (or an approved admission
  workflow) creates the `student_profiles` row directly into `ACTIVE`; there
  is no pre-`ACTIVE` pending state in v1 (an "applicant" stage is out of
  scope — admissions decisioning happens outside Setu, Setu starts tracking a
  student once admitted). This is the transition `StudentAdmitted` is emitted
  from — the canonical spec names this event explicitly (§8) but the
  original state-machine pass omitted the creation step entirely, jumping
  straight to post-creation transitions. `ACTIVE ↔ ON_LEAVE` (student/admin-initiated, reversible);
  `ACTIVE → SUSPENDED` (disciplinary or academic, `COLLEGE_ADMIN` only,
  requires a linked `audit_logs` reason — never AI-initiated, canonical spec
  §15); `SUSPENDED → ACTIVE` (reinstatement); `ACTIVE → GRADUATED` (system
  transition, triggered by an approved `graduation_applications` row reaching
  `APPROVED`); `ACTIVE|ON_LEAVE|SUSPENDED → WITHDRAWN` (student/admin-initiated, terminal)
- **Validation**: `GRADUATED`/`WITHDRAWN` block new `enrollments`
- **Terminal**: `GRADUATED`, `WITHDRAWN`
- **Correction**: a mistaken `WITHDRAWN`/`GRADUATED` requires `SUPER_ADMIN`
  intervention and is exceptional, logged with elevated detail — not a
  routine transition
- **Audit**: every transition
- **Emitted event**: `StudentAdmitted`, `StudentSuspended`, `StudentGraduated`, `StudentWithdrawn`

## 8. Student Program

- **States**: `ACTIVE`, `ON_HOLD`, `COMPLETED`, `DISCONTINUED`
- **Transitions**: `ACTIVE ↔ ON_HOLD`; `ACTIVE → COMPLETED` (all curriculum
  requirements met, verified against `degree_progress`); `ACTIVE|ON_HOLD →
  DISCONTINUED`
- **Validation**: the "one active primary program" partial unique index
  (ERD audit #8) is checked on every `→ ACTIVE` transition where `is_primary = true`
- **Terminal**: `COMPLETED`, `DISCONTINUED`
- **Correction**: none — `ends_at` is set, a new `student_programs` row is
  created for a genuine re-admission
- **Audit**: yes
- **Emitted event**: `StudentProgramCompleted`, `StudentProgramDiscontinued`

## 9. Course

- **States**: `DRAFT`, `ACTIVE`, `DEPRECATED`, `RETIRED`
- **Transitions**: `DRAFT → ACTIVE` (catalog approval, `COLLEGE_ADMIN`);
  `ACTIVE → DEPRECATED` (no new sections may be scheduled, existing sections
  unaffected); `DEPRECATED → RETIRED` (no sections reference it in any
  `CURRENT`/future `academic_period`)
- **Validation**: cannot `RETIRE` a course still referenced by a `curriculum_requirement_courses`
  row in an active curriculum
- **Terminal**: `RETIRED`
- **Correction**: `DEPRECATED → ACTIVE` is allowed (un-deprecate); `RETIRED` is one-way
- **Audit**: yes
- **Emitted event**: `CourseDeprecated`, `CourseRetired`

## 10. Course Section

- **States**: `DRAFT`, `OPEN`, `CLOSED`, `CANCELLED`
- **Transitions**: `DRAFT → OPEN` (faculty assigned, room/meeting pattern set,
  by `COLLEGE_ADMIN`); `OPEN → CLOSED` (registration window ends or capacity
  policy closes it); `DRAFT|OPEN → CANCELLED` (before the period starts,
  `COLLEGE_ADMIN`)
- **Validation**: `CANCELLED` requires zero `ACTIVE` enrollments, or an
  explicit bulk-drop-and-notify flow (not silent) — cancelling a section with
  enrolled students is a significant action requiring the drop of every
  affected `enrollments` row (each individually transitioned, not a bypass of
  the enrollment state machine) and a `notification` to each affected student
- **Terminal**: `CANCELLED`, `CLOSED` (a period-end close; `CLOSED` sections
  are historical, not reopened)
- **Correction**: `CLOSED → OPEN` only within the same registration window, audited
- **Audit**: yes
- **Emitted event**: `CourseSectionOpened`, `CourseSectionCancelled`

## 11. Enrollment

- **States**: `REQUESTED`, `ENROLLED`, `WAITLISTED`, `DROPPED`, `COMPLETED`, `REJECTED`
- **Transitions**:
  - `REQUESTED → ENROLLED` — system, after the full invariant chain
    (erd/02-academic.md) passes, capacity available
  - `REQUESTED → WAITLISTED` — system, invariant chain passes except capacity
  - `REQUESTED → REJECTED` — system, invariant chain fails and no applicable
    `enrollment_overrides` exists
  - `WAITLISTED → ENROLLED` — system, triggered by a capacity-freeing drop
    elsewhere, promotes by `waitlist_entries.position`
  - `ENROLLED → DROPPED` — student (within drop deadline) or `COLLEGE_ADMIN`
  - `ENROLLED → COMPLETED` — system, at period close, if attendance/assessment
    requirements were met (not a blanket auto-pass)
- **Validation**: every inbound transition to `ENROLLED` re-validates capacity
  under `SELECT ... FOR UPDATE` (transaction-concurrency-matrix.md) — a
  promotion from waitlist is not exempt from this even though it "should" have
  a seat, because a concurrent cancellation of the same seat is possible
- **Terminal**: `COMPLETED`, `REJECTED`, `DROPPED` (a drop is terminal for
  *that* enrollment row's active lifecycle, though the row is reused/reopened
  on genuine re-enrollment rather than a new row being created, per the ERD note)
- **Correction/rollback**: a drop can be reversed by `COLLEGE_ADMIN` only
  within a defined grace window, via an explicit `enrollment_overrides` row
  (type `REINSTATEMENT`) — never a silent status flip
- **Audit**: every transition writes `enrollment_actions` (append-only)
- **Emitted event**: `StudentEnrolled`, `StudentWaitlisted`, `EnrollmentDropped`, `EnrollmentCompleted`

## 12. Waitlist Entry

- **States**: `WAITING`, `OFFERED`, `EXPIRED`, `CONVERTED`, `WITHDRAWN`
- **Transitions**: `WAITING → OFFERED` (seat freed, this entry is next by
  `position`, a time-boxed offer window opens); `OFFERED → CONVERTED`
  (student accepts within the window, triggers `Enrollment: WAITLISTED →
  ENROLLED`); `OFFERED → EXPIRED` (window lapses without acceptance, seat
  offered to the next position); `WAITING|OFFERED → WITHDRAWN` (student removes
  themself)
- **Validation**: `position` values are re-compacted (no gaps) whenever an
  entry leaves the list, within the same transaction as the removal
- **Terminal**: `CONVERTED`, `EXPIRED` (entry itself is done, though the seat
  offer cascades to the next entry), `WITHDRAWN`
- **Correction**: none
- **Audit**: yes
- **Emitted event**: `WaitlistOffered`, `WaitlistConverted`, `WaitlistExpired`

## 13. Enrollment Override

- **States**: `ACTIVE`, `USED`, `EXPIRED`, `REVOKED`
- **Transitions**: created directly `ACTIVE` by `COLLEGE_ADMIN` approval;
  `ACTIVE → USED` (consumed by exactly one enrollment attempt — an override is
  single-use unless explicitly created as multi-use, which is a distinct type
  not modeled in v1); `ACTIVE → EXPIRED` (time-based); `ACTIVE → REVOKED`
  (admin rescinds before use)
- **Validation**: a `USED` override cannot be reused for a second enrollment attempt
- **Terminal**: `USED`, `EXPIRED`, `REVOKED`
- **Correction**: none — a new override is created, not an old one reinstated
- **Audit**: creation and consumption both audited, linked to the specific
  `enrollment_actions` row that consumed it
- **Emitted event**: `EnrollmentOverrideGranted`, `EnrollmentOverrideConsumed`

## 14. Attendance Session

- **States**: `OPEN`, `CLOSED`
- **Transitions**: `OPEN → CLOSED` — by the opening faculty member, or
  automatically at `ends_at`
- **Validation**: only one `OPEN` session per `meeting_id` (partial unique
  index) — a faculty member cannot open a second concurrent session for the
  same class meeting
- **Terminal**: `CLOSED`
- **Correction**: none — a closed session is not reopened; late/manual
  attendance marks are made as manual `attendance_records` correction entries
  referencing the closed session, not by reopening it
- **Audit**: open/close both audited
- **Emitted event**: `AttendanceSessionOpened`, `AttendanceSessionClosed`

## 15. Attendance Record

- **States**: `PRESENT`, `LATE`, `ABSENT`, `EXCUSED`
- **Transitions**: initial mark set by QR scan (`PRESENT`/`LATE` based on
  timing) or defaults to `ABSENT` if no scan by session close (a
  system-generated row for every enrolled student with no scan, not an
  absence of a row — so "no record" never has to be interpreted as absent by
  every downstream reader); any state → any other state via **manual
  correction only**, requires `corrected_by` and a `reason`
- **Validation**: corrections require faculty-of-section or `COLLEGE_ADMIN`
  permission, and are only allowed for a `CLOSED` attendance session (an
  `OPEN` session's records are still live and shouldn't need "correcting")
- **Terminal**: none in the strict sense — any state can be corrected — but
  once the enclosing academic period is `CLOSED`, corrections additionally
  require `COLLEGE_ADMIN` + a documented exception reason (a higher bar than
  routine same-week corrections)
- **Correction/rollback**: this *is* the correction mechanism — see ERD note;
  every correction is its own audited event, the row is updated but the
  correction itself is never silent
- **Audit**: every correction logged with old value, new value, corrector, reason
- **Emitted event**: `AttendanceRecorded` (initial), `AttendanceCorrected` (any
  correction)

## 25. Faculty Attendance (staff day record)

- **States**: `PRESENT`, `HALF_DAY`, `ABSENT`, `ON_LEAVE`; for `PRESENT`/`HALF_DAY` the row is also *open* (checked in, not
  yet out) or *complete* (`check_out_at` set)
- **Transitions**: (no row) → `PRESENT` by the faculty member's own **check-in** (server time, today in the college timezone);
  `PRESENT` open → complete by their own **check-out**; any state → any other via **administrator marking or correction only**
  (an admin may also create the row for a day the faculty member never checked in)
- **Validation**: self check-in only for the caller's own `ACTIVE` faculty profile, only for *today*, and only if no row exists yet
  (an admin-recorded `ABSENT`/`ON_LEAVE` is not overridden by a later self check-in — the admin corrects it); check-out only after
  a check-in and only once; faculty cannot create, edit or backdate any other day; admin marking requires a `reason`; no future dates
- **Policy rules** (college's current policy, docs/erd `staff_attendance_policies`): at check-in, if the college-local check-in time is later than
  shift start + grace, `late_minutes` = minutes after shift start; at check-out, `worked_minutes` = check-out − check-in; a `PRESENT` day
  with fewer than the full-day minutes becomes `HALF_DAY` automatically, and fewer than the half-day minutes also sets `short_hours`.
  The evaluation stores the `policy_version` used. Administrator marks and approved regularisations keep their stated status (a person
  decided) but still record the metrics for any times they set. A past day with a check-in and no check-out is shown as **missing
  check-out** (derived at read time) and can be fixed with a regularisation request.
- **Terminal**: none — an administrator can always correct a day
- **Concurrency**: `UNIQUE (faculty_id, attendance_date)` + optimistic `version`
- **Audit**: check-in, check-out and every admin mark/correction (old status → new status, actor, reason)
- **Emitted events** (payloads also carry `late_minutes`, `worked_minutes`, `policy_version`): `FacultyCheckedIn`, `FacultyCheckedOut`, `FacultyAttendanceMarked` (admin created the day),
  `FacultyAttendanceCorrected` (admin changed an existing day, carries the previous status) — via the transactional outbox, in
  the same transaction as the change; refused actions emit nothing. No consumer exists yet

## 26. Faculty Attendance Regularisation

- **States**: `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN`
- **Transitions**: (none) → `PENDING` by the faculty member for their OWN past day; `PENDING → APPROVED` or `REJECTED` by an
  administrator (`MANAGE_STAFF_ATTENDANCE`); `PENDING → WITHDRAWN` by the requester
- **Validation (request)**: own `ACTIVE` faculty profile; the day is strictly before today (today is handled by check-in) and within the
  last 30 days (`app.staff-attendance.regularisation-window-days`); requested status is `PRESENT`, `HALF_DAY` or `ON_LEAVE`; check-in/out
  times only for `PRESENT`/`HALF_DAY`, check-out after check-in; a reason is required; refused if an open request already exists for
  that day or if the day already says exactly what is being requested (nothing to change)
- **Validation (decision)**: only a `PENDING` request can be decided; a rejection needs a note; **nobody decides their own request**
  (an administrator who is also a faculty member cannot approve theirs)
- **Effect of approval**: in the SAME transaction the day in `faculty_attendance` is created or corrected to the requested status/times
  (source `ADMIN`, `corrected_by` = the approver, reason cites the regularisation) — never a silent edit
- **Terminal**: `APPROVED`, `REJECTED`, `WITHDRAWN`; a later mistake is fixed by an administrator's normal correction or a new request
- **Concurrency**: partial unique index (one `PENDING` per faculty per day) + optimistic `version`
- **Audit**: request, approval, rejection and withdrawal each audited
- **Emitted events**: `FacultyRegularisationRequested`, `FacultyRegularisationApproved`, `FacultyRegularisationRejected`,
  `FacultyRegularisationWithdrawn` (topic `setu.faculty_attendance`); an approval additionally emits the usual
  `FacultyAttendanceMarked`/`FacultyAttendanceCorrected` for the day it changed

## 16. Assignment Submission

- **States**: `DRAFT`, `SUBMITTED`, `LATE`, `GRADED`, `RESUBMISSION_REQUESTED`
- **Transitions**: `DRAFT → SUBMITTED` (student, before `due_at`) or `DRAFT →
  LATE` (student, after `due_at`, subject to `late_policy`); `SUBMITTED|LATE →
  GRADED` (faculty, produces a `grades` row via the evaluation step);
  `GRADED → RESUBMISSION_REQUESTED` (faculty, exceptional — e.g. academic
  integrity review) → back to `DRAFT` for a new `attempt_no`
- **Validation**: `attempt_no` increments on resubmission, prior attempts retained
- **Terminal**: `GRADED` (unless resubmission requested)
- **Correction**: resubmission cycle is the correction path; no direct edit of
  a graded submission's content
- **Audit**: yes
- **Emitted event**: `AssignmentSubmitted`, `AssignmentGraded`

## 17. Assessment

- **States**: `DRAFT`, `SCHEDULED`, `OPEN`, `CLOSED`, `PUBLISHED`, `VOIDED`
- **Transitions**: `DRAFT → SCHEDULED` (components/weights validated to sum
  correctly — the publish-time check from the ERD note, actually enforced
  here at `SCHEDULED`, before students can see it); `SCHEDULED → OPEN` (at
  `starts_at`); `OPEN → CLOSED` (at `ends_at` or manual close); `CLOSED →
  PUBLISHED` (faculty releases results — this is what makes `grades` rows
  visible to students, not the grade rows' own `status`); `DRAFT|SCHEDULED →
  VOIDED` (cancelled before ever opening)
- **Validation**: `total_marks` and component structure immutable from
  `SCHEDULED` onward (ERD note) — a correction after this point requires
  voiding and recreating, not editing in place, to protect any attempts
  already taken
- **Terminal**: `PUBLISHED`, `VOIDED`
- **Correction**: none post-`PUBLISHED` at the assessment level (individual
  `grades` corrections happen at the grade entity, not by unpublishing the assessment)
- **Audit**: yes, especially the publish action
- **Emitted event**: `AssessmentPublished`

## 18. Assessment Attempt

- **States**: `NOT_STARTED`, `IN_PROGRESS`, `SUBMITTED`, `EVALUATED`, `VOIDED`
- **Transitions**: `NOT_STARTED → IN_PROGRESS` (student starts, within the
  assessment's `OPEN` window); `IN_PROGRESS → SUBMITTED` (student submits, or
  system auto-submits at `ends_at`); `SUBMITTED → EVALUATED` (auto-grader or
  faculty produces `score`, feeds the evaluation step toward a `grades` row);
  `NOT_STARTED|IN_PROGRESS → VOIDED` (academic-integrity or technical-failure
  exception, `COLLEGE_ADMIN`/faculty)
- **Validation**: a second `IN_PROGRESS` attempt for the same
  `(assessment_id, student_id)` is blocked unless the assessment explicitly
  allows multiple attempts (`attempt_no` increments)
- **Terminal**: `EVALUATED`, `VOIDED`
- **Correction**: a `VOIDED` attempt is excluded from grade evaluation
  entirely — not a zero score, an exclusion
- **Audit**: yes
- **Emitted event**: `AssessmentAttemptSubmitted`, `AssessmentCompleted`
  (on evaluation)

## 19. Grade

- **States**: `DRAFT`, `PUBLISHED`, `SUPERSEDED`
- **Transitions**: `DRAFT → PUBLISHED` (faculty publish action, sets
  `published_at`/`published_by`); `PUBLISHED → SUPERSEDED` (a correction is
  published — the *old* row transitions to `SUPERSEDED`, a *new* row is
  inserted as `PUBLISHED`, per the append-only ERD convention — this is not an
  in-place edit)
- **Validation**: only a `PUBLISHED` (not `DRAFT`) grade can be superseded;
  you can't "correct" a draft, you just edit it
- **Terminal**: `SUPERSEDED` (for that row — the lineage as a whole is never terminal)
- **Correction/rollback**: this state machine *is* the correction/history
  mechanism (canonical spec "published grades require correction history")
- **Audit**: every publish and every supersede
- **Emitted event**: `GradePublished` (on every `→ PUBLISHED`, including
  corrections — consumers distinguish an original publish from a correction
  via `supersedes_id` being null or not)

## 20. Payment

- **States**: `INITIATED`, `SUCCEEDED`, `FAILED`, `REFUNDED`
- **Transitions**: `INITIATED → SUCCEEDED|FAILED` — **driven only by a
  `payment_events` webhook row**, never by client-reported status (canonical
  spec §5); `SUCCEEDED → REFUNDED` (admin-initiated, provider-confirmed)
- **Validation**: the transition handler is idempotent against
  `payment_events.provider_event_id` (ERD note) — processing the same webhook
  twice must not double-apply the state change or the `student_accounts.balance` adjustment
- **Terminal**: `FAILED`, `REFUNDED`; `SUCCEEDED` is terminal unless refunded
- **Correction**: a failed payment is not retried in place — a new `payments`
  row is created for a retry attempt
- **Audit**: every `payment_events` row is itself the audit trail
- **Emitted event**: `PaymentSuccessful`, `PaymentFailed`, `PaymentRefunded`

## 21. Risk Score

- **States**: none in the traditional sense — `risk_scores` rows are
  **append-only snapshots**, not a mutable entity with a lifecycle. "State" is
  represented by `band` (`LOW`/`MEDIUM`/`HIGH`) at a point in time.
- **Transitions**: a new calculation inserts a new row; there is no
  transition *of* a row, only a new row superseding the previous "current" one
- **Validation**: `rule_version` and `feature_snapshot` must both be present
  (reproducibility requirement, ERD note)
- **Terminal**: N/A (append-only)
- **Correction**: a miscalculated score is never edited — a corrected
  recalculation is simply the next row; if the *rule itself* was wrong, that's
  a `rule_version` bump, and old scores remain historically accurate to the
  rule version they were computed under
- **Audit**: implicit — the append-only table is its own audit trail
- **Emitted event**: `RiskScoreChanged` (emitted when the new row's `band`
  differs from the previous current row's `band` — not on every recalculation
  that doesn't change the band, to avoid alert fatigue)

## 22. Recommendation

- **States**: `PROPOSED`, `ACCEPTED`, `DISMISSED`
- **Transitions**: created `PROPOSED` by either the rule engine or `ai-explain`
  (`generated_by` records which); `PROPOSED → ACCEPTED` — **human action only**
  (advisor/faculty), creates a linked `interventions` row; `PROPOSED →
  DISMISSED` — human action, with optional reason
- **Validation**: an AI-generated recommendation cannot self-transition to
  `ACCEPTED` — canonical spec §15's "AI deciding..." ban applies to
  recommendations becoming actions, not just to grades/attendance
- **Terminal**: `ACCEPTED`, `DISMISSED`
- **Correction**: none
- **Audit**: yes, including `generated_by`
- **Emitted event**: `RecommendationCreated`

## 23. Intervention

- **States**: `PLANNED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`
- **Transitions**: linear, each by the `owner_user_id` (the assigned advisor)
  or `COLLEGE_ADMIN`
- **Validation**: `COMPLETED` requires at least one `intervention_actions` row
  documenting the outcome (an intervention can't be silently closed with no record)
- **Terminal**: `COMPLETED`, `CANCELLED`
- **Correction**: a completed intervention can be reopened
  (`COMPLETED → IN_PROGRESS`) by `COLLEGE_ADMIN` only, audited, if outcome
  tracking reveals it wasn't actually resolved
- **Audit**: every `intervention_actions` row
- **Emitted event**: `InterventionCreated`, `InterventionCompleted`

## 24. Graduation Application

- **States**: `SUBMITTED`, `UNDER_REVIEW`, `APPROVED`, `REJECTED`, `WITHDRAWN`
- **Transitions**: `SUBMITTED → UNDER_REVIEW` (`COLLEGE_ADMIN` picks it up);
  `UNDER_REVIEW → APPROVED` (eligibility re-checked live against current
  `degree_progress`/`curriculum_requirements`, per the ERD note — not the
  submission-time snapshot); `UNDER_REVIEW → REJECTED` (with reason);
  `SUBMITTED|UNDER_REVIEW → WITHDRAWN` (student-initiated)
- **Validation**: `APPROVED` triggers `Student: ACTIVE → GRADUATED` in the
  same transaction
- **Terminal**: `APPROVED`, `REJECTED`, `WITHDRAWN`
- **Correction**: a mistaken `APPROVED` requires `SUPER_ADMIN` exceptional
  intervention (mirrors the Student entity's graduated-in-error case) — not a
  routine reversal
- **Audit**: yes, reviewer identity and reason required on `REJECTED`
- **Emitted event**: `GraduationApproved`, `GraduationRejected`
