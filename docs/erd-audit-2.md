# Setu — ERD Audit Pass 2 (all ~84 tables)

Per your request: reviewed every table in [ERD.md](ERD.md)/[erd/](erd/) for
duplication, unjustified technical tables, derivable-but-persisted data,
missing FK/relationships, missing tenant ownership, missing lifecycle/history,
circular ownership, and Sprint 6 extraction risk. Format per finding: issue →
why it's wrong → corrected model → impact. Nothing below removes a table to
reduce count — every finding either adds a missing safeguard, clarifies an
already-correct design, or flags a genuine open question.

## A. Missing tenant ownership — real IDOR risk, not style

**Issue**: five join tables connect two *independently* tenant-owned entities
but don't carry their own `tenant_id`:

| Table | Joins | Risk |
|---|---|---|
| `curriculum_requirement_courses` | `curriculum_requirements` ↔ `courses` | nothing stops linking a requirement in tenant A to a course in tenant B |
| `class_students` | `classes` ↔ `student_profiles` | could enroll a student from tenant B into tenant A's cohort |
| `class_faculty` | `classes` ↔ `faculty_profiles` | same pattern |
| `job_skills` | `jobs` ↔ `skills` | same pattern |
| `student_skills` | `student_profiles` ↔ `skills` | same pattern |

**Why it's wrong**: each individual FK is valid on its own — the row would
insert fine even if the two sides belong to different tenants, because
nothing checks that both sides *agree* on tenant. This is exactly the
cross-tenant IDOR/BOLA class of bug the canonical spec calls out (§7 in your
latest instruction, "object IDs must always be checked against authorization
scope").

**Corrected model**: add `tenant_id UUID NOT NULL REFERENCES colleges(id)` to
all five, plus a `CHECK`/trigger (a plain `CHECK` can't reference another
table, so this needs either a `BEFORE INSERT/UPDATE` trigger or — cleaner —
enforcement in the owning module's application service, verifying both parent
rows' `tenant_id` equal the join row's `tenant_id` before insert) rather than
relying on the two FKs alone.

**Impact**: five tables gain a column; the application-layer insert path for
each gets one extra guard clause. Applied directly to the ERD files (no
approval needed — this is closing an unambiguous gap, not a design choice).

**Not flagged, checked and cleared**: `guardian_access_permissions` (pure
child of one tenant-owned parent, not a cross-entity join — inherits tenant
scope safely via `grant_id`), `student_guardians`, `course_prerequisites`,
`meeting_patterns`, `faculty_assignments`, `enrollments`, `waitlist_entries`
all already carry explicit `tenant_id`.

## B. Derivable-but-persisted: `student_academic_periods`

**Issue**: `term_gpa`, `credits_attempted`, `credits_earned` on
`student_academic_periods` are arithmetically derivable from summing/averaging
`student_academic_records` rows for that period. As written, this read like
"just another cached total" without saying so, which invites drift bugs (a
recompute path that forgets to update this row).

**Why it's wrong-as-written**: a value that's silently expected to "stay in
sync" with its source, with no stated mechanism for *how*, is a data-integrity
risk — not wrong to store, but wrong to leave implicit.

**Corrected model**: explicit **derived-then-frozen** pattern, the same shape
already used for `grades`/`transcripts`: `student_academic_periods` is
recomputed live from `student_academic_records` while the period is
`IN_PROGRESS`, then **posted** (a `posted_at` timestamp, analogous to
`grades.published_at`) at period close — after which it's authoritative and
frozen, not recomputed even if something upstream is later corrected (a
late correction after term close creates a new correction trail, exactly like
a grade correction, rather than silently rewriting a posted term record).

**Impact**: add `posted_at TIMESTAMPTZ NULL` to the table; document the
recompute-until-posted rule. No new table, no approval needed — this is a
clarification of intent that was already implied by "never delete after
official posting" but not made mechanically explicit.

## C. Underspecified reference: `risk_factors.evidence_ref`

**Issue**: `evidence_ref TEXT` is a loose free-text field standing in for
"which records justify this factor" — but evidence can come from
`attendance_records`, `grades`, or `learning_progress`, three different
tables. A single FK column can't point at three different tables (the
classic polymorphic-association problem), so `TEXT` was a placeholder, not a
real design.

**Why it's wrong**: the canonical spec requires risk output to carry "factor
evidence IDs" for reproducibility (§11 5E) — a free-text field doesn't
actually guarantee that, it just happens to look like it does.

**Corrected model**: `evidence_refs JSONB NOT NULL DEFAULT '[]'`, an array of
`{"table": "attendance_records", "id": "<uuid>"}` objects — a deliberate,
typed polymorphic reference instead of an accidental untyped one.

**Impact**: one column type change in the Risk module. No approval needed —
this replaces an underspecified placeholder with what the requirement
actually called for.

## D. Scope of `idempotency_keys` was unclear enough to look redundant

**Issue**: on first read, `idempotency_keys` looks like it might duplicate two
things that already have their own dedup mechanism: `payment_events`
(idempotent via `provider_event_id` unique constraint) and Kafka consumers
(which the spec says should keep **their own** per-consumer processed-event
tables, not share one global table).

**Why it's not actually redundant**: there's a third, distinct case neither of
those cover — **API-request-level idempotency**, e.g. a client-supplied
`Idempotency-Key` header on `POST /enrollments` so a flaky mobile connection
retrying a request doesn't create two enrollment attempts. TRD's own
non-functional requirement ("attendance check-ins and enrollment actions must
be safe to retry") needs exactly this, and it's neither a payment webhook nor
a Kafka consumer.

**Corrected model**: keep the table, but its purpose is now explicit and
narrowed: **API-request idempotency only** (`key` = client-supplied header
value, `scope` = the endpoint/action). Payment webhook dedup stays on
`payment_events.provider_event_id`; Kafka consumer dedup stays in each
consumer's own table, per canonical spec §8 — this table is not a substitute
for either.

**Impact**: no schema change, a purpose clarification. Worth stating because
an implementer hitting all three "idempotency" concepts without this
distinction would likely misuse one table for all three and create the exact
coupling problem the per-consumer-table guidance was trying to avoid.

## E. `api_clients` — recommend deferring entirely from Sprint 5

**Issue**: this table has no consumer anywhere in the Sprint 5 scope — it
exists for a hypothetical future machine-to-machine integration that isn't
part of the canonical spec's product scope (§1) or Sprint 5 checklist (§11).

**Why it's wrong to include now**: an empty, unused table sitting in every
migration from day one is exactly the kind of "add infrastructure only when
it has a clear engineering/business reason" violation the canonical spec
warns against (§17.10) — it's speculative, not derivable, and not currently justified.

**Corrected model**: remove from the Sprint 5 migration set entirely. If/when
a real M2M integration is scoped, it arrives as its own migration at that
point, with a real consumer driving its exact shape (which fields it actually
needs may differ from a guess made now).

**Impact**: **this is architectural — flagged for your approval, not applied.**
Removing a documented table (even an unused one) from the build plan is a
scope call, not a bug fix.

## F. Checked for duplication — none found, three pairs verified distinct

- `class_faculty` (cohort-level advisor/coordinator) vs. `faculty_assignments`
  (section-level teaching assignment, primary/co-instructor/TA) — different
  scope of responsibility, correctly separate.
- `enrollment_actions` / `intervention_actions` (domain-specific, typed action
  trail consumed by application logic — "show this student's enrollment
  history") vs. `audit_logs` (generic security/compliance before-after log
  across every module) — different consumers, correctly separate.
- `student_academic_periods` (period-level posted summary) vs.
  `student_academic_records` (per-enrollment line item within a period) — a
  summary/line-item relationship, not a duplicate; see finding B above for the
  derivation clarification.

## G. Checked for circular ownership — none found

Walked the FK graph across all four ERD files looking for any A → B → A
cycle at the table level (which would block clean migration ordering and
Sprint 6 extraction). None found. The one cross-module reference worth naming
explicitly: `departments.hod_user_id → users(id)` reaches from `institution`
directly into `identity` — one-directional, and per the module-boundary rule
this must be resolved via an application call (validating the user has a
`FACULTY` role in the right tenant), never a DB-level join across the module
boundary once schemas are split (see schema-per-module-map.md).

## H. Sprint 6 extraction risk — module count vs. candidate service count

**Issue**: Sprint 5 has 16 modules (TRD §2); the canonical spec's Sprint 6
candidate service list (§12) names only 11: Identity, Institution/Academic,
Learning, Assessment, Attendance, Student Success, Career, Finance,
Notification/Realtime, AI, Analytics, API Gateway. **`directory`,
`guardian-access`, `institution`, `catalog`, `enrollment`, and `record` don't
each get their own named service** — several Sprint 5 modules are expected to
fold into shared Sprint 6 services, and the canonical spec doesn't say exactly
which.

**Why this matters now, not just at Day 16**: two tables in particular sit at
a genuine ownership fork and are worth flagging before more is built on top
of them:

- **`persons`** — is shared by `student_profiles`, `faculty_profiles`, and
  `guardians`, which conceptually could end up split across different
  services. Recommended: `persons` belongs with `Identity` (it's the closest
  thing to "who is this human," adjacent to `users`), with the profile tables
  living in whichever service ends up owning student/faculty/guardian
  academic data — referenced by `person_id` as an **opaque cross-service ID**
  post-extraction, never joined directly.
- **`record` module tables** (`student_academic_records`, `transcripts`,
  `degree_progress`, `graduation_applications`) — not named in the Sprint 6
  candidate list at all. Recommended: fold into **Assessment** (transcripts
  and academic records derive directly from grade publication, a tighter
  coupling than to curriculum/Academic), not Institution/Academic — but this
  is genuinely a judgment call, not a fact derivable from the source material.

**Impact**: **architectural, flagged for approval, not applied.** The
Sprint-5 module boundaries in TRD §2 don't need to change either way — this
only affects which Sprint 6 service each module's tables land in, which the
canonical spec itself says gets validated at Day 16 against real measured
coupling, not decided upfront. Recording the two live questions now so
Day 16 isn't starting from zero.

## I. Ephemeral table reconsidered: `attendance_qr_sessions`

**Issue** (carried forward from the original ERD note, now resolved rather
than left open): this table's rows are short-lived rotating nonces — a
natural fit for Redis rather than Postgres.

**Resolution**: keep it in Postgres for Sprint 5. A Redis-only nonce would be
lost on a Redis restart mid-session, silently breaking an in-progress
attendance window with no recovery path — a worse failure mode than the small
storage overhead of keeping these rows in Postgres. Revisit as a Sprint 6/
performance optimization once real load data justifies the move, not before.
