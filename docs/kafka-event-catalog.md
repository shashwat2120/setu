# Setu — Kafka Event Catalog

Every event carries the canonical envelope (canonical spec §8):
`event_id, event_type, event_version, occurred_at, producer, tenant_id,
aggregate_type, aggregate_id, correlation_id, causation_id, schema_version,
payload`. Columns below cover only what varies per event.

**`tenant_id` is nullable in the envelope** — corrected in the final
reconciliation (finding #6): `identity.users` and `identity.sessions` are
explicitly tenant-**independent** in the ERD (a global login can hold roles
across multiple tenants), so `UserSuspended`/`SessionRevoked` have no single
tenant to populate. Every other event in this catalog does carry a real
`tenant_id`; the two identity-level exceptions are the only ones, matching
the same nullable-tenant_id exception already established for `audit_logs`
in the ERD.

**Every event's `event_version` is `1`** as of this catalog — stated
explicitly per finding #4, since the original pass left it implicit. Bump an
individual event's version only when its payload shape changes; this is not
a global counter.

**Ordering**: partition key = `aggregate_id` for every event unless noted —
guarantees per-aggregate ordering (e.g. all events for one `enrollment_id`
land in order) without requiring a single global partition.

**Idempotency**: every consumer keeps its own processed-event table keyed on
`event_id` (canonical spec §8 — no shared global table, see
erd-audit-2.md finding D) and applies changes via deterministic
upsert, never a blind append.

**Retry/DLQ**: transient failures (DB connection blip) retry with backoff, 3
attempts; a poison message (fails deserialization or violates an invariant
the consumer can't resolve) goes to `{topic}.DLQ` after retries exhaust —
never silently dropped, never blocks the partition indefinitely.

`state-machines.md` now names **49 distinct emitted events** across the 24
required entities (48 original + `StudentAdmitted`, added to the Student
state machine in this same pass since the canonical spec names it explicitly
but no transition previously emitted it). **All 49 are below** — 24 added
this pass (marked 🆕: 23 originally missing + `StudentAdmitted`), 25 already
present — **zero gaps**. Plus 4 pre-existing events outside the 24-entity
list (`Transcript`×2, `PlacementRecorded`, `AIJobCompleted`) — **53 rows
total**. `tenant_id` follows the envelope rule above (NULL only for the
identity-level events marked *platform*); every other row carries the tenant
shown implicitly by its aggregate. `event_version` is `1` for every row.

> **Added with staff attendance (9 further rows, implemented):** `FacultyCheckedIn`, `FacultyCheckedOut`,
> `FacultyAttendanceMarked`, `FacultyAttendanceCorrected`, the four `FacultyRegularisation*` events and `FacultyAttendancePolicyChanged` — aggregate `faculty_attendance`, topic `setu.faculty_attendance`, written
> to the outbox in the same transaction as the change. They carry the standard envelope (event id, tenant id, correlation id,
> `event_version` 1); `previous_status`, `actor_user_id` and `reason` are present only on the two administrator events. The
> consumers listed are the intended Sprint 6B ones; the only one built so far is Notification's (next section).

## Implementation status

**35 events are emitted today:** `GradePublished`; `AttendanceRecorded`, **`AttendanceSessionOpened`, `AttendanceSessionClosed`** and the 8 staff-attendance events; `PlacementRecorded`;
`StudentAdmitted`; **`StudentEnrolled`, `StudentWaitlisted`, `WaitlistConverted`, `EnrollmentDropped`, `CourseSectionOpened`, `CourseSectionCancelled`** (added by the Sprint 6 coupling
audit: the Academic service had published nothing, and Learning, Attendance, Student Success, Record and Notification need them);
`PaymentSuccessful`, `PaymentFailed`, `PaymentRefunded`; `PasskeyRevoked`, `SessionRevoked`, `UserSuspended`, `UserDeactivated`,
`UserReactivated`, **`UserRoleAssigned`, `UserRoleRevoked`** (the last three are not in the table below; the two role events were added by Sprint 6A.2 so other services can build a local role read model, see [contracts/identity-service.md](contracts/identity-service.md)); `InterventionCreated`, `InterventionCompleted`, `RecommendationCreated`,
`RiskScoreChanged`. **The other 30 catalogued events are not emitted yet**; [extraction-audit.md](extraction-audit.md) section 7 says which
ones block which extraction. Enrollment events use the enrollment id as the Kafka key (aggregate `enrollments`); `WaitlistConverted` uses
the section id (aggregate `waitlist_entries`) so promotion order is kept per section.

## Consumers that exist

**Notification** (`com.setu.notification`, built in the monolith, same contract it will keep when extracted). Consumer group
`setu-notification`, reading `setu.grades`, `setu.payments`, `setu.placement_records`, `setu.recommendations`,
`setu.interventions`, `setu.student_profiles`, `setu.enrollments` and `setu.waitlist_entries` from the earliest offset. It turns these events into rows in
`notification.notifications`; every other event type on those topics is acknowledged and left alone.

| Event | Notification for | Type |
|---|---|---|
| `GradePublished` | the student who owns the enrollment | `GRADE_PUBLISHED` |
| `PaymentSuccessful` / `PaymentFailed` / `PaymentRefunded` | the student | `PAYMENT_RECEIVED` / `PAYMENT_FAILED` / `PAYMENT_REFUNDED` |
| `PlacementRecorded` | the student | `PLACEMENT_RECORDED` |
| `RecommendationCreated` | the student | `RECOMMENDATION_AVAILABLE` |
| `StudentAdmitted` | the student | `ADMISSION_CONFIRMED` |
| `StudentWaitlisted` | the student (with their position) | `WAITLISTED` |
| `WaitlistConverted` | the student, now enrolled | `WAITLIST_PROMOTED` |
| `InterventionCreated` | the intervention's owner (advisor), not the student | `INTERVENTION_ASSIGNED` |

- **Idempotency:** the event id (`event_id` header) is inserted into `notification.processed_events` in the same transaction as the
  notification; a redelivery hits the primary key and does nothing. If the notification cannot be written, the id rolls back with it,
  so the retry is not mistaken for a duplicate.
- **Recipient:** derived server-side from the event's ids (student to user through `directory`, enrollment to student through
  `enrollment`), never from anything in the message about a person. The tenant in the event must equal the tenant of the person it
  resolves to, and the `tenant_id` header must equal the payload's; otherwise the event is poison.
- **Retry and DLQ:** a transient failure is delivered 3 times in all (backoff 0.5 s, then 1 s), then published to `{topic}.DLQ`. Malformed
  JSON, a missing field, an unknown student/enrollment, a tenant mismatch or a constraint violation is poison: straight to the DLQ,
  no retries. A dead-lettered record keeps its original key and value, plus the failure in Kafka's standard `kafka_dlt-*` headers.
- **Does not block the application:** the listener is started after the application is up and retried every 10 s if the Kafka client
  cannot be created. On this Windows machine that can take minutes; until then events wait on the topic.
- **Metrics:** `setu_notification_events_total{event_type, outcome}` (outcome `created`, `duplicate`, `ignored`, `error`) and
  `setu_notification_events_dead_lettered_total{topic}`.
- **Switches:** `app.notification.consumer.enabled` (default on; the test suite turns it off so only an explicit test consumes),
  `app.notification.consumer.group-id`, `app.notification.consumer.topic-prefix`, `app.notification.consumer.aggregates`.
- **Shown to the user:** `GET /notifications` and the bell in the app header (see openapi-contract-catalog.md). Not built: email and
  push delivery (`notification.delivery_attempts`).

| Event | Producer | Consumers | Aggregate | Ordering key | Payload essentials |
|---|---|---|---|---|---|
| `UserSuspended` | Identity | Notification, Analytics | `users` | `user_id` | `user_id, reason, actor_user_id` |
| `UserDeactivated` 🆕 | Identity | Notification, Analytics | `users` | `user_id` *(tenant: platform)* | `user_id, actor_user_id, self_initiated` |
| `PasskeyRevoked` 🆕 | Identity | Notification (security alert) | `passkeys` | `user_id` *(tenant: platform)* | `passkey_id, user_id, revoked_by` |
| `SessionRevoked` | Identity | Notification (security alert), Analytics | `sessions` | `user_id` | `session_id, user_id, reason` |
| `CollegeSuspended` 🆕 | Institution/Academic | Notification, Analytics | `colleges` | `college_id` *(tenant = self)* | `college_id, reason, actor_user_id` |
| `CollegeArchived` 🆕 | Institution/Academic | Notification, Analytics | `colleges` | `college_id` *(tenant = self)* | `college_id, actor_user_id` |
| `AcademicYearActivated` 🆕 | Institution/Academic | Notification, Analytics | `academic_years` | `tenant_id` | `academic_year_id, code` |
| `AcademicYearClosed` 🆕 | Institution/Academic | Notification, Analytics | `academic_years` | `tenant_id` | `academic_year_id, code` |
| `RegistrationOpened` 🆕 | Institution/Academic | Notification, Enrollment (self), Analytics | `academic_periods` | `period_id` | `period_id, academic_year_id, registration_open_at` |
| `RegistrationClosed` 🆕 | Institution/Academic | Notification, Enrollment (self), Analytics | `academic_periods` | `period_id` | `period_id, academic_year_id, registration_close_at` |
| `PeriodClosed` 🆕 | Institution/Academic | Record (triggers `student_academic_periods` posting), Notification, Analytics | `academic_periods` | `period_id` | `period_id, academic_year_id` |
| `StudentAdmitted` 🆕 | Institution/Academic | Directory (self, profile setup), Notification, Analytics | `student_profiles` | `student_id` | `student_id, admission_no, admission_date` |
| `StudentSuspended` 🆕 | Institution/Academic | Notification, Guardian-access (grant-review trigger, not auto-revoke), Analytics | `student_profiles` | `student_id` | `student_id, reason, actor_user_id` |
| `StudentGraduated` 🆕 | Institution/Academic | Academic Record, Notification, Analytics | `student_profiles` | `student_id` | `student_id, graduation_application_id` |
| `StudentWithdrawn` 🆕 | Institution/Academic | Notification, Analytics | `student_profiles` | `student_id` | `student_id, actor_user_id` |
| `StudentProgramCompleted` 🆕 | Institution/Academic | Notification, Analytics | `student_programs` | `student_id` | `student_program_id, student_id, program_id` |
| `StudentProgramDiscontinued` 🆕 | Institution/Academic | Notification, Analytics | `student_programs` | `student_id` | `student_program_id, student_id, reason` |
| `CourseDeprecated` 🆕 | Institution/Academic | Notification, Analytics | `courses` | `course_id` | `course_id, code` |
| `CourseRetired` 🆕 | Institution/Academic | Notification, Analytics | `courses` | `course_id` | `course_id, code` |
| `StudentEnrolled` | Institution/Academic | Learning, Attendance, Notification, Analytics, Record | `enrollments` | `enrollment_id` | `enrollment_id, section_id, student_id, tenant_id` |
| `StudentWaitlisted` | Institution/Academic | Notification | `enrollments` | `enrollment_id` | `enrollment_id, section_id, student_id, position` |
| `WaitlistOffered` 🆕 | Institution/Academic | Notification | `waitlist_entries` | `section_id` *(same reasoning as `WaitlistConverted` below)* | `waitlist_entry_id, enrollment_id, section_id, student_id, offer_expires_at` |
| `WaitlistConverted` | Institution/Academic | Notification, Attendance | `waitlist_entries` | `section_id` (promotion order matters per-section) | `waitlist_entry_id, enrollment_id, section_id, student_id` |
| `WaitlistExpired` 🆕 | Institution/Academic | Notification | `waitlist_entries` | `section_id` | `waitlist_entry_id, section_id, student_id` |
| `EnrollmentDropped` | Institution/Academic | Attendance, Learning, Notification, Analytics | `enrollments` | `enrollment_id` | `enrollment_id, section_id, student_id, dropped_at` |
| `EnrollmentCompleted` 🆕 | Institution/Academic | Record, Analytics | `enrollments` | `enrollment_id` | `enrollment_id, section_id, student_id, period_id` |
| `EnrollmentOverrideGranted` 🆕 | Institution/Academic | Notification, Analytics | `enrollment_overrides` | `student_id` | `enrollment_override_id, student_id, section_id, type, approved_by` |
| `EnrollmentOverrideConsumed` 🆕 | Institution/Academic | Analytics | `enrollment_overrides` | `student_id` | `enrollment_override_id, enrollment_id` |
| `CourseSectionOpened` | Institution/Academic | Notification, Analytics | `course_sections` | `section_id` | `section_id, course_id, period_id` |
| `CourseSectionCancelled` | Institution/Academic | Enrollment (self, to cascade drops — see transaction-concurrency-matrix.md), Notification | `course_sections` | `section_id` | `section_id, affected_enrollment_ids[]` |
| `AttendanceSessionOpened` | Attendance | Notification (optional), Realtime/WebSocket relay | `attendance_sessions` | `attendance_session_id` *(corrected — see reconciliation finding #5)* | `session_id, section_id, meeting_id` |
| `AttendanceSessionClosed` 🆕 | Attendance | Realtime/WebSocket relay | `attendance_sessions` | `attendance_session_id` | `session_id, section_id, closed_at` |
| `AttendanceRecorded` | Attendance | Realtime/WebSocket relay, Student Success, Analytics | `attendance_records` | `attendance_session_id` (per-session order matters for the live board) | `attendance_record_id, session_id, enrollment_id, status, marked_at` |
| `AttendanceCorrected` | Attendance | Realtime relay, Student Success, Analytics | `attendance_records` | `attendance_session_id` | `attendance_record_id, old_status, new_status, corrected_by, reason` |
| `FacultyCheckedIn` | Attendance | Analytics | `faculty_attendance` | `faculty_id` | `faculty_attendance_id, faculty_id, attendance_date, status, check_in_at, actor_user_id` |
| `FacultyCheckedOut` | Attendance | Analytics | `faculty_attendance` | `faculty_id` | `faculty_attendance_id, faculty_id, attendance_date, status, check_in_at, check_out_at, actor_user_id` |
| `FacultyAttendanceMarked` | Attendance | Notification, Analytics | `faculty_attendance` | `faculty_id` | `faculty_attendance_id, faculty_id, attendance_date, status, actor_user_id, reason` |
| `FacultyAttendanceCorrected` | Attendance | Notification, Analytics | `faculty_attendance` | `faculty_id` | `faculty_attendance_id, faculty_id, attendance_date, previous_status, status, actor_user_id, reason` |
| `FacultyAttendancePolicyChanged` | Attendance | Notification, Analytics | `faculty_attendance_policy` | `tenant_id` | `policy_version, shift_start, shift_end, grace_minutes, full_day_minutes, half_day_minutes, actor_user_id` |
| `FacultyRegularisationRequested` | Attendance | Notification, Analytics | `faculty_attendance` | `faculty_id` | `regularisation_id, faculty_id, attendance_date, requested_status, actor_user_id, reason` |
| `FacultyRegularisationApproved` | Attendance | Notification, Analytics | `faculty_attendance` | `faculty_id` | `regularisation_id, faculty_id, attendance_date, requested_status, actor_user_id, note` |
| `FacultyRegularisationRejected` | Attendance | Notification, Analytics | `faculty_attendance` | `faculty_id` | `regularisation_id, faculty_id, attendance_date, requested_status, actor_user_id, note` |
| `FacultyRegularisationWithdrawn` | Attendance | Analytics | `faculty_attendance` | `faculty_id` | `regularisation_id, faculty_id, attendance_date, requested_status, actor_user_id` |
| `AssignmentSubmitted` | Learning | Notification | `assignment_submissions` | `assignment_id` | `submission_id, assignment_id, student_id, attempt_no` |
| `AssignmentGraded` | Learning | Assessment (feeds grade production), Notification | `assignment_submissions` | `assignment_id` | `submission_id, grade_id` |
| `AssessmentPublished` | Assessment | Notification, Analytics | `assessments` | `assessment_id` | `assessment_id, section_id` |
| `AssessmentAttemptSubmitted` | Assessment | Analytics | `assessment_attempts` | `assessment_id` | `attempt_id, assessment_id, student_id` |
| `AssessmentCompleted` | Assessment | Student Success, Notification, Analytics | `assessment_attempts` | `student_id` (a student's own progression must be ordered) | `attempt_id, assessment_id, student_id, outcome` |
| `GradePublished` | Assessment | **Academic Record** (writes `student_academic_records`), Notification, Student Success, Analytics | `grades` | `enrollment_id` | `grade_id, enrollment_id, component_id, version, supersedes_id \| null` — consumers distinguish an original publish (`supersedes_id = null`) from a correction |
| `TranscriptRequested` | Academic Record | Academic Record (self, async job trigger) | `transcripts` | `student_id` | `transcript_id, student_id` |
| `TranscriptGenerated` | Academic Record | Notification | `transcripts` | `student_id` | `transcript_id, document_ref, version` |
| `GraduationApproved` | Academic Record | Institution/Academic (updates `students.status`), Notification, Analytics | `graduation_applications` | `student_id` | `application_id, student_id, approved_by` |
| `GraduationRejected` | Academic Record | Notification | `graduation_applications` | `student_id` | `application_id, student_id, reason` |
| `PaymentSuccessful` | Finance | Notification, Institution/Academic (hold resolver), Analytics | `payments` | `student_account_id` | `payment_id, student_id, charge_id, amount, currency` |
| `PaymentFailed` | Finance | Notification | `payments` | `student_account_id` | `payment_id, student_id, reason` |
| `PaymentRefunded` 🆕 | Finance | Notification, Analytics | `payments` | `student_account_id` | `payment_id, student_id, amount, refunded_by` |
| `PlacementRecorded` | Career | Notification, Analytics | `placement_records` | `student_id` | `placement_id, student_id, employer` |
| `RiskScoreChanged` | Student Success | Notification (advisor alert), Analytics, AI (context refresh, read-only) | `risk_scores` | `student_id` | `risk_score_id, student_id, old_band \| null, new_band, rule_version` — **emitted only when `band` actually changes** (state machine note — avoids alert fatigue) |
| `RecommendationCreated` | Student Success | Notification | `recommendations` | `student_id` | `recommendation_id, student_id, generated_by ('RULE'\|'AI')` |
| `InterventionCreated` | Student Success | Notification | `interventions` | `student_id` | `intervention_id, student_id, owner_user_id` |
| `InterventionCompleted` | Student Success | Analytics | `interventions` | `student_id` | `intervention_id, outcome` |
| `AIJobCompleted` | AI | Notification, Realtime/WebSocket relay | `ai_jobs` (in-memory/Redis job tracking, not a persisted table — see TRD/ERD: AI has no tables) | `job_id` | `job_id, requested_by, status ('COMPLETED'\|'FAILED')` |

## Notes on specific ordering/idempotency risks

- **`GradePublished` corrections**: since `grades` is append-only
  (state-machines.md #19), a correction publishes a *new* event, not an
  update to an old one. Academic Record's consumer must apply corrections in
  `version` order — if messages arrive out of order (unlikely given the
  `enrollment_id` partition key, but not impossible after a rebalance), the
  consumer checks `version` against its own last-applied version before
  writing, discarding a stale-order delivery rather than regressing the record.
- **`WaitlistConverted`**: partitioned by `section_id`, not `enrollment_id`,
  specifically so that promotions for the same section are strictly ordered
  relative to each other — two promotions racing out of order for the same
  section would double-assign a seat.
- **`CourseSectionCancelled` → cascading drops**: this event is consumed by
  the *same* Institution/Academic service that produced it (a self-consumed
  event), specifically so the capacity-drop cascade runs as an async,
  observable, retryable process rather than a giant synchronous transaction
  touching every affected enrollment at once.
