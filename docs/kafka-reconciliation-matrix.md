# Setu — Kafka Reconciliation Matrix

**STATUS: RESOLVED.** All 23 events flagged `✗ MISSING` below, plus
`StudentAdmitted`, have been added to
[kafka-event-catalog.md](kafka-event-catalog.md) (marked 🆕 there), carrying
forward exactly the producer/consumer/aggregate_id/tenant_id/ordering-key
already worked out in this table. `state-machines.md` #7 (Student) was also
updated with the previously-missing creation transition. The table below is
kept as-is as the **point-in-time record of the gap analysis** — read
[kafka-event-catalog.md](kafka-event-catalog.md) for current state, not the
✗ marks here.

Every event emitted anywhere in [state-machines.md](state-machines.md) (the
24 requested entities), plus every row already in
[kafka-event-catalog.md](kafka-event-catalog.md), cross-checked. Default
idempotency strategy for every row unless noted: per-consumer processed-event
table keyed on `event_id`. Default retry/DLQ for every row unless noted:
3 retries with backoff, then `{topic}.DLQ`.

Legend: ✅ = present and consistent. **✗ MISSING** = state machine names this
event, catalog does not have it. ⚠ = present but with a flagged issue.

| State-machine transition | Emitted event | Catalog status | Producer | Consumers | aggregate_id | tenant_id | Ordering key |
|---|---|---|---|---|---|---|---|
| User: `ACTIVE→SUSPENDED` | `UserSuspended` | ✅ | Identity | Notification, Analytics | `user_id` | NULL (platform — see envelope note) | `user_id` |
| User: `→DEACTIVATED` | `UserDeactivated` | **✗ MISSING** | Identity | Notification, Analytics | `user_id` | NULL | `user_id` |
| Passkey: `ACTIVE→REVOKED` | `PasskeyRevoked` | **✗ MISSING** | Identity | Notification (security alert) | `passkey_id` | NULL | `user_id` |
| Session: security revoke | `SessionRevoked` | ✅ | Identity | Notification, Analytics | `session_id` | NULL | `user_id` |
| College: `→SUSPENDED` | `CollegeSuspended` | **✗ MISSING** | Institution/Academic | Notification, Analytics | `college_id` | `college_id` (self) | `college_id` |
| College: `→ARCHIVED` | `CollegeArchived` | **✗ MISSING** | Institution/Academic | Notification, Analytics | `college_id` | `college_id` | `college_id` |
| Academic Year: `→CURRENT` | `AcademicYearActivated` | **✗ MISSING** | Institution/Academic | Notification, Analytics | `academic_year_id` | tenant | `tenant_id` |
| Academic Year: `→CLOSED` | `AcademicYearClosed` | **✗ MISSING** | Institution/Academic | Notification, Analytics | `academic_year_id` | tenant | `tenant_id` |
| Academic Period: `→REGISTRATION_OPEN` | `RegistrationOpened` | **✗ MISSING** | Institution/Academic | Notification, Enrollment (self), Analytics | `period_id` | tenant | `period_id` |
| Academic Period: `→REGISTRATION_CLOSED` | `RegistrationClosed` | **✗ MISSING** | Institution/Academic | Notification, Enrollment (self), Analytics | `period_id` | tenant | `period_id` |
| Academic Period: `→CLOSED` | `PeriodClosed` | **✗ MISSING** | Institution/Academic | Record (triggers `student_academic_periods` posting — see note), Notification, Analytics | `period_id` | tenant | `period_id` |
| Student: `→SUSPENDED` | `StudentSuspended` | **✗ MISSING** | Institution/Academic | Notification, Guardian-access (policy: does suspension affect grants? — flagged), Analytics | `student_id` | tenant | `student_id` |
| Student: `→GRADUATED` | `StudentGraduated` | **✗ MISSING** | Institution/Academic | Academic Record, Notification, Analytics | `student_id` | tenant | `student_id` |
| Student: `→WITHDRAWN` | `StudentWithdrawn` | **✗ MISSING** | Institution/Academic | Notification, Analytics | `student_id` | tenant | `student_id` |
| Student Program: `→COMPLETED` | `StudentProgramCompleted` | **✗ MISSING** | Institution/Academic | Notification, Analytics | `student_program_id` | tenant | `student_id` |
| Student Program: `→DISCONTINUED` | `StudentProgramDiscontinued` | **✗ MISSING** | Institution/Academic | Notification, Analytics | `student_program_id` | tenant | `student_id` |
| Course: `→DEPRECATED`/`RETIRED` | `CourseDeprecated`, `CourseRetired` | **✗ MISSING (both)** | Institution/Academic | Notification, Analytics | `course_id` | tenant | `course_id` |
| Course Section: `→OPEN` | `CourseSectionOpened` | ✅ | Institution/Academic | Notification, Analytics | `section_id` | tenant | `section_id` |
| Course Section: `→CANCELLED` | `CourseSectionCancelled` | ✅ | Institution/Academic | Institution/Academic (self, cascade), Notification | `section_id` | tenant | `section_id` |
| Enrollment: `→ENROLLED` | `StudentEnrolled` | ✅ | Institution/Academic | Learning, Attendance, Notification, Analytics, Record | `enrollment_id` | tenant | `enrollment_id` |
| Enrollment: `→WAITLISTED` | `StudentWaitlisted` | ✅ | Institution/Academic | Notification | `enrollment_id` | tenant | `enrollment_id` |
| Enrollment: `→DROPPED` | `EnrollmentDropped` | ✅ | Institution/Academic | Attendance, Learning, Notification, Analytics | `enrollment_id` | tenant | `enrollment_id` |
| Enrollment: `→COMPLETED` | `EnrollmentCompleted` | **✗ MISSING** | Institution/Academic | Record, Analytics | `enrollment_id` | tenant | `enrollment_id` |
| Waitlist: `→OFFERED` | `WaitlistOffered` | **✗ MISSING** | Institution/Academic | Notification | `waitlist_entry_id` | tenant | `section_id` |
| Waitlist: `→CONVERTED` | `WaitlistConverted` | ✅ | Institution/Academic | Notification, Attendance | `waitlist_entry_id` | tenant | `section_id` *(deliberate — justified in catalog notes)* |
| Waitlist: `→EXPIRED` | `WaitlistExpired` | **✗ MISSING** | Institution/Academic | Notification | `waitlist_entry_id` | tenant | `section_id` |
| Override: `→GRANTED`/consumed | `EnrollmentOverrideGranted`, `EnrollmentOverrideConsumed` | **✗ MISSING (both)** | Institution/Academic | Notification, Analytics | `enrollment_override_id` | tenant | `student_id` |
| Attendance Session: `→OPEN` | `AttendanceSessionOpened` | ✅ *(ordering key corrected this pass)* | Attendance | Notification, Realtime relay | `attendance_session_id` | tenant | `attendance_session_id` |
| Attendance Session: `→CLOSED` | `AttendanceSessionClosed` | **✗ MISSING** | Attendance | Realtime relay | `attendance_session_id` | tenant | `attendance_session_id` |
| Attendance Record: initial mark | `AttendanceRecorded` | ✅ | Attendance | Realtime relay, Student Success, Analytics | `attendance_record_id` | tenant | `attendance_session_id` |
| Attendance Record: correction | `AttendanceCorrected` | ✅ | Attendance | Realtime relay, Student Success, Analytics | `attendance_record_id` | tenant | `attendance_session_id` |
| Assignment Submission: submit/grade | `AssignmentSubmitted`, `AssignmentGraded` | ✅ (both) | Learning | Notification / Assessment, Notification | `submission_id` | tenant | `assignment_id` |
| Assessment: `→PUBLISHED` | `AssessmentPublished` | ✅ | Assessment | Notification, Analytics | `assessment_id` | tenant | `assessment_id` |
| Assessment Attempt: submit/evaluate | `AssessmentAttemptSubmitted`, `AssessmentCompleted` | ✅ (both) | Assessment | Analytics / Student Success, Notification, Analytics | `attempt_id` | tenant | `assessment_id` / `student_id` |
| Grade: `→PUBLISHED` | `GradePublished` | ✅ | Assessment | Academic Record, Notification, Student Success, Analytics | `grade_id` | tenant | `enrollment_id` |
| Payment: `→SUCCEEDED`/`FAILED`/`REFUNDED` | `PaymentSuccessful`, `PaymentFailed`, `PaymentRefunded` | ⚠ 2 of 3 present | Finance | Notification, Institution/Academic (hold resolver), Analytics | `payment_id` | tenant | `student_account_id` |
| Risk Score: band change | `RiskScoreChanged` | ✅ | Student Success | Notification, Analytics, AI (context refresh) | `risk_score_id` | tenant | `student_id` |
| Recommendation: `→PROPOSED` | `RecommendationCreated` | ✅ | Student Success | Notification | `recommendation_id` | tenant | `student_id` |
| Intervention: create/complete | `InterventionCreated`, `InterventionCompleted` | ✅ (both) | Student Success | Notification / Analytics | `intervention_id` | tenant | `student_id` |
| Graduation Application: approve/reject | `GraduationApproved`, `GraduationRejected` | ✅ (both) | Academic Record | Institution/Academic, Notification, Analytics | `application_id` | tenant | `student_id` |

## Events in the catalog with no formal backing state machine (finding #2)

Not illegitimate — these entities simply weren't among the 24 required —
flagged for completeness, not correctness:

- `TranscriptRequested`, `TranscriptGenerated` (Transcript has no formal
  state machine in state-machines.md)
- `PlacementRecorded` (Placement has no formal state machine)
- `AIJobCompleted` — the one **architecturally deliberate** exception:
  AI has no persisted table by design (TRD §6), so it can never have a formal
  DB-backed state machine. Already self-documented in the catalog.

## Not found anywhere — the canonical spec's own named example

**`StudentAdmitted`** is explicitly named as an example event in
`FINAL_CANONICAL_PROJECT_SPEC.md` §8's producer/consumer table, but no
document in this repo ever defines it: the Student state machine (#7 in
state-machines.md) starts *at* `ACTIVE` with no transition modeling the
initial admission/creation itself, so there's no state-machine transition to
attach the event to. This is the single most notable gap in the whole
reconciliation — a canonical-spec-named event with zero implementation
anywhere. **Recommend**: add an explicit `Student: (none)→ACTIVE` creation
transition to state-machines.md #7, emitting `StudentAdmitted`.

## Recommended addition outside the 24-entity list

`guardian_access_grants` has a real lifecycle (`ACTIVE`/`EXPIRED`/`REVOKED`,
documented in the ERD, exercised in transaction-concurrency-matrix.md
scenario 8) but — because Guardian Access Grant wasn't among the 24 entities
originally requested — has no formal state-machine entry and no
`GuardianAccessGranted`/`GuardianAccessRevoked` events. Given canonical spec
§3's explicit "access is explicit, scoped, revocable and **audited**"
non-negotiable, an event trail for grant/revoke is arguably load-bearing, not
optional polish. Recommended, not applied without approval since it expands
scope beyond what was asked.

## Summary count (as of resolution)

- **24 required entities** → **49 distinct emitted events** named in
  state-machines.md (48 original + `StudentAdmitted`, added this pass)
- **49 of 49 present and consistent** in kafka-event-catalog.md — **zero gaps**
- **4 catalog events** have no formal backing state machine (acceptable —
  out-of-scope entities, one — `AIJobCompleted` — by design since AI has no
  persisted table)
- **53 total rows** in kafka-event-catalog.md (49 + 4)
- 1 ordering-key inconsistency (`AttendanceSessionOpened`), 1 envelope gap
  (`tenant_id` nullability), 1 completeness gap (`event_version`) — all
  corrected in the prior pass, still in effect
