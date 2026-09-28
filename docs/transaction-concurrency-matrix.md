# Setu — Transaction / Concurrency Matrix

Every scenario below: the race condition, the locking/isolation strategy,
the transaction boundary, and the expected outcome. Default Postgres
isolation is `READ COMMITTED` throughout — scenarios that need something
stronger say so explicitly rather than assuming it.

## 1. Last-seat enrollment race

**Scenario**: two students submit `POST /enrollment/enrollments` for the same
section at nearly the same instant; only one seat remains.

**Strategy**: `SELECT capacity, (SELECT COUNT(*) FROM enrollments WHERE
section_id = ? AND status = 'ENROLLED') AS taken FROM course_sections WHERE
id = ? FOR UPDATE` — the row lock on `course_sections` serializes the two
requests; the second transaction blocks until the first commits, then
re-reads the now-current count. Not a `SELECT ... FOR UPDATE` on
`enrollments` (there's no single row to lock there — the race is over the
*section's* remaining capacity, a derived count).

**Transaction boundary**: the entire enrollment invariant chain
(erd/02-academic.md) is one transaction, `FOR UPDATE` acquired at the start
against `course_sections`, released at commit/rollback.

**Outcome**: first transaction to acquire the lock gets the seat
(`enrollments.status = 'ENROLLED'`); the second, on re-checking the count
after the lock releases, finds capacity exhausted and transitions its own
enrollment to `WAITLISTED` (not an error — the state machine's normal path).

## 2. Waitlist promotion race

**Scenario**: two enrollments drop from the same section at nearly the same
instant, each independently trying to promote "the next" waitlisted student.

**Strategy**: promotion runs inside the *same transaction* as the drop that
freed the seat (not a separately-scheduled job racing against other drops).
`SELECT * FROM waitlist_entries WHERE section_id = ? AND status = 'WAITING'
ORDER BY position ASC LIMIT 1 FOR UPDATE SKIP LOCKED` — `SKIP LOCKED` lets
the second concurrent drop's promotion skip an entry another transaction is
already promoting, rather than blocking on it, so two simultaneous drops
promote two *different* students instead of one blocking transaction winning
and the other silently promoting nobody.

**Transaction boundary**: drop + promotion-if-applicable, one transaction.

**Outcome**: N simultaneous drops correctly promote up to N distinct
waitlisted students (bounded by waitlist length), each `WaitlistConverted`
event emitted exactly once per promotion.

## 3. Duplicate QR scan

**Scenario**: a flaky mobile connection causes the same scan request to be
sent twice, or two devices somehow scan for the same student in the same
session.

**Strategy**: `attendance_records`' composite unique `(attendance_session_id,
enrollment_id)` is the actual guard — the second insert attempt hits a unique
violation at the database level, not just an application-level check-then-insert
race (which would have a TOCTOU gap). The application catches the unique
violation and returns the *existing* record's status (idempotent success),
not a 500 error.

**Transaction boundary**: single-row insert, no explicit locking needed —
the unique constraint *is* the concurrency control.

**Outcome**: exactly one `attendance_records` row per (session, enrollment)
regardless of how many scan attempts arrive; the second (and any further)
attempt returns the same result as the first, not an error.

## 4. Concurrent attendance correction

**Scenario**: a faculty member and a `COLLEGE_ADMIN` both try to correct the
same attendance record at the same time (e.g. one says `PRESENT`, the other
says `EXCUSED`).

**Strategy**: `attendance_records` needs a `version` column (an addition
this matrix surfaces — not yet in the ERD, flagged below) for optimistic
locking; the correction endpoint requires `If-Match: "<version>"`
(openapi-contract-catalog.md convention). The second writer's `PATCH` fails
with `409 VERSION_CONFLICT` and must re-fetch + re-apply, not silently
overwrite.

**Transaction boundary**: single-row update with `WHERE id = ? AND version =
?`, checking the affected-row count is exactly 1.

**Outcome**: last-to-attempt (not last-to-click) loses and must explicitly
retry with knowledge of the intervening change — no silent lost update.
**ERD correction needed**: add `version INT NOT NULL DEFAULT 0` to
`attendance_records` (erd/03-learning-assessment-records.md currently omits
it — flagged here as a small ERD gap this exercise surfaced).

## 5. Duplicate payment webhook

**Scenario**: the payment provider retries a webhook delivery (its own
network hiccup, or a slow `200 OK` from Setu it interprets as a failure).

**Strategy**: `payment_events.provider_event_id` unique constraint (already
in the ERD) — the second delivery's insert hits a unique violation, caught
and treated as a no-op success (return `200 OK` immediately without
reprocessing), never a second `student_accounts.balance` adjustment.

**Transaction boundary**: `payment_events` insert + `payments.status` update
+ `student_accounts.balance` adjustment + outbox `PaymentSuccessful`, one
transaction, short-circuited entirely (no balance touch at all) if the
`provider_event_id` insert conflicts.

**Outcome**: N webhook deliveries for the same provider event → exactly one
balance adjustment, N-1 fast no-op responses.

## 6. Concurrent grade update

**Scenario**: two faculty members (co-instructors) both try to publish/correct
the same (enrollment, component) grade at the same time.

**Strategy**: `grades` is append-only (state-machines.md #19) — there's no
in-place row to lock a race on. Instead, the *insert* of a new version races:
`INSERT ... SELECT max(version) + 1 FROM grades WHERE enrollment_id = ? AND
component_id = ? FOR UPDATE` — the `FOR UPDATE` on the existing rows for that
pair serializes the version-number computation, preventing two concurrent
corrections from both computing `version = 3` and creating a duplicate.
Enforce with a composite unique `(enrollment_id, component_id, version)` as a
second guard even if the locking is implemented correctly, since a unique
constraint is cheaper to reason about as a safety net than trusting the lock alone.

**Transaction boundary**: version-number read (locked) + new-row insert +
`is_current` flag flip on the old row + outbox `GradePublished`, one transaction.

**Outcome**: two simultaneous corrections produce two sequential versions
(3, then 4), never two rows both claiming version 3; the second
co-instructor's correction is applied *on top of* the first, not lost.

## 7. Refresh-token reuse

**Scenario**: a refresh token is used to mint a new access token, rotating to
a new refresh token — then the *same old* refresh token is presented again
(a sign of token theft, or a client bug re-sending a cached request).

**Strategy**: on rotation, the old `sessions` row's `refresh_token_hash` is
invalidated (either `revoked_at` set and a *new* `sessions` row created for
the rotated token, or the hash is updated in place — recommend a new row per
rotation, linked by a `family_id`, so "revoke the whole family" is a single
`UPDATE ... WHERE family_id = ?` rather than a graph walk). Presenting an
already-rotated (revoked) token triggers **family-wide revocation** — every
session in that family is revoked, forcing full re-authentication.

**Transaction boundary**: the reuse-detection check + family revocation is
one transaction, must complete before any new token is issued from that
request (fail closed, not open).

**Outcome**: legitimate rotation succeeds normally; reuse of a stale token
kills the entire session family rather than just rejecting the one request —
treating it as a compromise signal, not a simple retry.

## 8. Guardian grant revoke/access race

**Scenario**: a `COLLEGE_ADMIN` revokes a guardian's access grant at the same
instant the guardian is mid-request reading student data.

**Strategy**: the authorization pipeline (canonical spec §6) checks
`guardian_access_grants.status = 'ACTIVE'` fresh on *every* request — there
is no request-lifetime caching of "is this grant active" that could serve a
stale `true` past the revoke. The `version` column on `guardian_access_grants`
(already in the ERD) means the revoke itself is a safe optimistic-locked
update; the *read* side's safety comes from never caching the grant check
across a request boundary, not from locking.

**Transaction boundary**: revoke is a single-row update, independent of any
in-flight read transaction (`READ COMMITTED` means the in-flight read simply
doesn't see the revoke — it was already past the check when the revoke
committed, which is correct: a request that started before revocation
legitimately completes under the permissions that were active when it began).

**Outcome**: no read succeeds against a grant that was already `REVOKED`
*before* that read's authorization check ran; a read whose authorization
check happened a moment before the revoke committing is allowed to finish —
this is the correct, standard "no new access after revoke, in-flight
requests aren't torn" semantics, not a bug.

## 9. Cache invalidation race

**Scenario**: a value changes (e.g. a grade is published) at the same moment
a request reads the Redis-cached dashboard aggregate that value feeds into —
does the cache get invalidated before or after the read?

**Strategy**: **write-through invalidation in the same transaction's outbox
flow, not a best-effort post-commit step.** The pattern: the business
transaction commits (grade published) → outbox event `GradePublished` is
published → the consumer that owns the cached read model invalidates/
recomputes the relevant Redis key (`{tenant}:{domain}:{resource}:{objectId}:
{version}` per TRD §9) *before* acknowledging the Kafka message. A read that
lands in the gap between commit and cache invalidation gets a
**briefly-stale** cached value — acceptable (this is exactly why the cache
key includes a version/filter component and TTL is short), **never**
acceptable would be a cache invalidation that runs *before* the underlying
write commits (which could invalidate, then have a concurrent read repopulate
the cache with the *stale* pre-write value, now stuck until the next real change).

**Transaction boundary**: cache invalidation is explicitly **outside** the
DB transaction (it's a Kafka-consumer-triggered side effect, after commit) —
called out because conflating them (e.g. trying to invalidate Redis inside
the same DB transaction) would tie cache correctness to DB transaction
success/failure in a way that doesn't actually help and adds a distributed
dependency to every write path.

**Outcome**: cache is eventually consistent with a bounded staleness window
(the time between commit and consumer processing, typically sub-second), and
never permanently stuck on a stale value because every invalidation is
tied to a real, ordered domain event.

## 10. Outbox publish failure

**Scenario**: the business transaction commits successfully (e.g. an
enrollment is created), but the outbox publisher — the background process
relaying `outbox_events` rows to Kafka — fails to actually deliver the
message (Kafka unreachable, network partition).

**Strategy**: the outbox row is written in the *same* transaction as the
business change (TRD §4 — this is the whole point of the pattern), so the
`StudentEnrolled` event's existence is never in question, only its
*delivery timing*. The publisher polls `outbox_events WHERE status = 'NEW'`,
attempts delivery, and only flips `status = 'PUBLISHED'` on Kafka
acknowledgment — a failed delivery leaves the row `NEW`, to be retried on the
next poll cycle. **The business transaction itself never rolls back because
of a publish failure** — publish failure is a downstream, retryable concern,
not a reason to undo a real, already-committed enrollment.

**Transaction boundary**: business write + outbox insert = one transaction
(already committed by the time this scenario occurs); the publish attempt
itself is a *separate*, retryable, idempotent operation (Kafka producer
idempotence + the row's own `status` field prevent a double-publish on retry).

**Outcome**: worst case, a bounded delay between "enrollment exists in
Postgres" and "downstream consumers (Learning, Attendance, Notification) find
out about it" — never a lost event, never a business change silently undone
because Kafka happened to be down at that moment. A stuck `NEW` row past a
alerting threshold (e.g. 5 minutes unpublished) is an operational alert, not
a silent failure.
