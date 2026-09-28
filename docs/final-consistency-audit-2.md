# Setu — Final Cross-Artifact Consistency Audit (Round 2)

Covers all 11 artifacts listed in your request, plus the Kafka reconciliation
matrix and migration-order validation done as part of this pass. Findings
follow the same rule as every prior audit: unambiguous gaps (missing
tenant_id, missing index, wrong ordering key, duplicate draft content) were
closed directly; anything that's a real design choice is reported, not
silently resolved.

## A. PASS / FAIL

**PASS, as of this update.** The prior round's single blocker — 23 missing
Kafka events plus the unimplemented `StudentAdmitted` — is resolved: all 24
were added to [kafka-event-catalog.md](kafka-event-catalog.md) carrying
forward the exact producer/consumer/aggregate_id/tenant_id/ordering-key
already worked out in the reconciliation matrix, and `state-machines.md` #7
(Student) now has the previously-missing creation transition. Combined with
the zero FK-ordering violations already verified in section E, there is no
remaining item that blocks Sprint 5A implementation.

## B. Critical issues remaining

**None.** The two prior critical items are resolved (see A). Verified
specifically per this round's requirements:
- **Zero state-machine events missing from the catalog** — 49/49 present.
- **Zero orphan Kafka events without a legitimate producer** — the 4 events
  outside the 24-entity list (`TranscriptRequested`/`Generated`,
  `PlacementRecorded`, `AIJobCompleted`) all have real, described producer
  logic in the OpenAPI catalog / TRD §6, just no *formal state-machine*
  backing (out of the originally-requested 24-entity scope, not illegitimate).
- **Zero duplicate semantic events** — the one close pair,
  `StudentGraduated` (Institution/Academic, aggregate `student_profiles`) and
  `GraduationApproved` (Academic Record, aggregate `graduation_applications`),
  are verified *not* a duplicate: one business transaction (approving a
  graduation application) legitimately changes two different aggregates, each
  gets its own event — standard practice, not redundancy.
- **Consistent** `event_version` (all `1`), `tenant_id` rules (nullable only
  for the two identity-level events), `aggregate_id`/ordering-key convention
  (aggregate's own id by default, two justified exceptions —
  `WaitlistConverted`/`WaitlistOffered`/`WaitlistExpired` on `section_id`,
  `AssessmentCompleted`/most student-scoped events on `student_id` — both
  explicitly reasoned in the catalog).
- **Commit-before-publish** — re-verified, still correct everywhere.

## C. Non-critical issues (can defer)

- `GuardianAccessGranted`/`GuardianAccessRevoked` — recommended addition,
  outside the originally-requested 24-entity scope; canonical spec's
  "audited" non-negotiable arguably wants this, but not blocking.
- `TranscriptRequested`/`TranscriptGenerated`/`PlacementRecorded` — present
  in the Kafka catalog with no formal backing state machine, because
  Transcript/Placement weren't in the 24-entity list. Low risk, worth a
  lightweight state machine each eventually.
- `openapi-contract-catalog.md` — Learning, most of Finance, Career, and
  Notification endpoints are described by pattern rather than individually
  enumerated (already disclosed honestly in that doc). Also missing an
  explicit `GET /catalog/students/{id}/degree-progress` row now that
  `degree_progress` is its own module (H3).
- `authorization-matrix.md`'s "Transcript/degree progress" row still
  presents them as one combined resource — cosmetic drift from the H3 module
  split; the actual permissions stated remain correct either way.
- Internal statement ordering within three migrations that bundle
  same-schema FK pairs in one file (`V24` buildings+rooms, `V58`
  skill_categories+skills, `V67` interventions+intervention_actions) — fine
  in principle (sequential statements in one transaction), needs the actual
  `CREATE TABLE` statement order to be respected when the SQL is written.

## D. Kafka reconciliation results

Full matrix: [kafka-reconciliation-matrix.md](kafka-reconciliation-matrix.md),
now marked RESOLVED. **49/49 state-machine-required events present, zero
gaps.** 24 events added this round (23 originally missing + `StudentAdmitted`),
each carrying forward its already-established producer/consumer/aggregate_id/
tenant_id/ordering-key/idempotency/retry-DLQ from the reconciliation matrix —
no new design decisions were made in applying them, per your instruction not
to redesign. Also still in effect from the prior pass: `AttendanceSessionOpened`'s
corrected ordering key, the documented `tenant_id` nullability exception, and
explicit `event_version: 1` on every row.

**Verified clean**: event-timing description (authoritative transaction →
outbox insert → commit → publish) is correct and consistent everywhere it
appears — TRD §4, the canonical spec transcription, and
transaction-concurrency-matrix.md scenario 10 all agree; no document ever
describes publish-before-commit. Consumer lists are singly-sourced in the
Kafka catalog by design (not duplicated, and not found to conflict, in
OpenAPI or TRD) — appropriate separation of concerns, not a gap.

## E. Migration-order validation

**Zero FK-ordering violations found** — every one of the 83 tables' FK
targets were checked individually against their own migration number; each
target lands at or before the migration that references it. Confirmed
specifically: `identity.user_roles` (V10) after `institution.colleges` (V6);
`catalog.meeting_patterns` (V29) after `enrollment.course_sections` (V28);
`btree_gist` extension (V3) before `meeting_patterns`' `EXCLUDE` constraint
(V29); `record` schema (V48+) after `assessment.grades` (V47).

**Cleaned up**: the document previously contained a superseded first-draft
migration ordering (an earlier, wrong `V5`–`V13` block) left in alongside the
corrected one — removed; the file now states the ordering once.

**Numbering**: V1–V71, sequential, no gaps, no duplicates. Confirmed.

## F. Index audit — all indexes added during this audit round

| Table | Columns | Query / use case | Why needed | Existing coverage |
|---|---|---|---|---|
| `enrollment.faculty_assignments` | `(faculty_id) WHERE ends_at IS NULL` | "What am I teaching" — faculty dashboard load | Original ERD pass only indexed the partial-unique `(section_id)` for the *primary-instructor* constraint; nothing indexed the reverse direction (faculty → their sections) | None — the existing partial unique index is keyed the other way and doesn't help a `WHERE faculty_id = ?` lookup |
| `risk.risk_scores` | `(student_id, calculated_at DESC)` | Risk score history/trend for a student; "current score" as `LIMIT 1` on this index | `risk_scores` had **no index at all** in the original pass beyond the implicit PK — a genuinely missing, not just suboptimal, index for a table that's read on every student/faculty dashboard | None |

**Considered and deliberately not added**: a general `(tenant_id, status)`
pattern across multiple tables, requested in section O's review list. No
specific documented query currently needs it — every `status`-filtered query
already identified in the ERD has a more specific composite index (e.g.
`enrollments (section_id, status)`). Adding a broad tenant+status index on
every status-bearing table without a named consumer would be exactly the
"indexes merely for quantity" the instruction warned against.

## G. Final counts

- **Entity/table count by module**: `identity` 7, `directory` 4,
  `guardian-access` 2, `institution` 4, `catalog` 12, `enrollment` 10,
  `attendance` 3, `learning` 6, `assessment` 6, `record` 4, `finance` 7,
  `career` 7, `risk` 5, `notification` 3, `governance` (cross-cutting) 3,
  `ai-explain` 0, `analytics` 0 — **83 tables total**
- **Table count by Postgres schema**: identical breakdown, 16 schemas — **83 tables total**
- **Kafka event count**: **53 total** (49 matching a required state-machine
  event, zero gaps + 4 extra: Transcript ×2, PlacementRecorded, AIJobCompleted)
- **API endpoint count**: 35 explicitly enumerated in
  openapi-contract-catalog.md; Learning/Finance/Career/Notification
  endpoints described by pattern, not individually counted (§C)
- **Migration count**: 71 (V1–V71)

## H. Final locked decisions (this round)

- `AttendanceSessionOpened` ordering key = `attendance_session_id`
- Kafka envelope `tenant_id` is nullable, exceptions limited to
  `UserSuspended`/`SessionRevoked`/(any future identity-level event)
- Every cataloged event's `event_version` starts at `1`
- `migration-order.md`'s ordering (V1–V71) is the single authoritative
  sequence — the superseded draft is removed, not just deprioritized
- `faculty_assignments(faculty_id)` and `risk_scores(student_id,
  calculated_at)` indexes are part of the ERD

## I. Deferred to Sprint 6 Day 16 (unchanged from prior rounds, restated for completeness)

- `student_academic_periods` — Record-owned in Sprint 5 (confirmed), Sprint
  6 *service* ownership still open
- Is Institution/Academic too large once real coupling is measured
- Does Academic Record justify its own service or fold back into Assessment
- Whether `guardian-access` ever needs independent extraction

## Remaining minor items, deferred (not blocking Sprint 5A)

- Add the two recommended guardian-access events (`GuardianAccessGranted`/
  `Revoked`) — still outside the requested 24-entity scope, not applied
  without separate approval
- Split `authorization-matrix.md`'s combined Transcript/degree-progress row
- Add the missing `degree-progress` GET endpoint to the OpenAPI catalog
- Enumerate Learning/Finance/Career/Notification endpoints individually in
  the OpenAPI catalog (currently pattern-described)
