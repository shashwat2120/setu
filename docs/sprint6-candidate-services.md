# Setu — Sprint 6 Candidate Service Map

Supersedes the plain list in
[FINAL_CANONICAL_PROJECT_SPEC.md](FINAL_CANONICAL_PROJECT_SPEC.md) §12 with
the approved decisions (E, H1, H2, H3). **All of these remain candidates**,
per your instruction — validated against actual measured coupling at Day 16
(`AGENTS.md` prompt 6A.1), not assumed correct just because they're written down here.

| Candidate service | Sprint 5 modules it absorbs | Why grouped this way |
|---|---|---|
| **Identity** | `identity` (now including `persons` — H1) | Canonical human identity + authentication, one coherent security boundary |
| **Institution/Academic** | `institution`, `catalog` (now including `degree_progress` — H3), `enrollment`, `directory` (profiles only, not `persons`), `guardian-access` (H2) | The academic structure + who's enrolled in it + guardian visibility into it — all read/written together constantly (a registrar screen touches all of these in one request) |
| **Learning** | `learning` | LMS content and progress — distinct read/write pattern from academic structure, high content volume |
| **Assessment** | `assessment` | Assessment execution, evaluation, grade *production* — stops at publish (H3) |
| **Academic Record** *(new — H3)* | `record` | The student's authoritative historical record — a distinct business responsibility from producing grades, explicitly not forced into Assessment. Consumes `GradePublished` async, doesn't own grading itself. |
| **Attendance** | `attendance` | High-frequency, realtime-coupled (WebSocket), independent scaling profile from everything else |
| **Student Success** | `risk` | The deterministic risk engine + intervention tracking — a distinct evaluation cadence (batch/scheduled) from request-driven modules |
| **Career** | `career` | Self-contained placement domain, weak coupling to academic core |
| **Finance** | `finance` | Payment-provider integration, PCI-adjacent isolation is valuable on its own |
| **Notification/Realtime** | `notification` | Fan-out and delivery — naturally a separate scaling/failure profile |
| **AI** | `ai-explain` | No tables of its own; isolated for the datasource-access boundary (TRD §6), not data volume |
| **Analytics** | `analytics` | Read-model projections, consumes events from every other service |
| **API Gateway** | *(none — new infrastructure)* | Routing, auth verification, rate limiting |

**13 candidates**, up from the canonical spec's original 11 — the two
additions are **Academic Record** (H3, explicit) and the absorption of
`guardian-access` into Institution/Academic being made explicit rather than
left unnamed (H2).

## Day 16 verdicts

The coupling audit ran: see [extraction-audit.md](extraction-audit.md). In short: the boundaries above hold, with two changes. **Record folds
into Assessment** (no code uses its tables; it is a read-side computation over grades and enrollments), and **Analytics is not a candidate
yet** (no code, no tables). Items 1, 2, 5 and 6 below are answered there: Institution/Academic stays one service (44 cross-schema foreign keys
inside it, nine services depending on it); `guardian-access` is not independent; Record is thin enough to fold; and the
`learning` ↔ `assessment` foreign key is the only database-level coupling between them, listed among the 34 frozen cross-service keys, to be
replaced using `GradePublished`.

## What Day 16 still needs to validate

Nothing above is final. Per canonical spec §12 and your own repeated
"validate, don't assume" framing, Day 16's coupling audit should specifically
check:

1. **Institution/Academic is not being split now** (explicit decision): it
   absorbs 5 of the original 16 Sprint 5 modules and stays that way through
   Sprint 5. Its internal subdomains — Institution (colleges/campuses/orgs/
   departments), Academic structure (catalog/curriculum), Enrollment
   (classes/sections/enrollment/waitlist), People (directory's profile
   tables), Guardian access — are recorded here as **internal extraction
   candidates for Day 16**, not a plan to act on before then.
2. **`guardian-access` is explicitly not an independent service candidate**
   at this stage (per your instruction) — it's part of Institution/Academic's
   People subdomain unless Day 16 measures a genuine, real service boundary
   (independent scaling, security isolation, or failure-domain need). Not
   presented as a candidate on the main table above for that reason.
3. **`student_academic_periods`** — confirmed Record-owned in Sprint 5 (see
   table-ownership-map.md), but its Sprint 6 *service* ownership (Academic
   Record vs. Institution/Academic, given its coupling to `academic_periods`)
   remains an open Day-16 question.
4. **`graduation_applications`** — confirmed under Academic Record,
   consuming (not owning) Institution/Academic's eligibility determination.
   Settled, not open.
5. **Does Academic Record actually need to be its own service**, or does
   Day 16's real coupling measurement show it's thin enough (3–4 tables) to
   fold into Assessment or Institution/Academic after all? Your instruction
   was explicit that this should be *validated*, not assumed — recorded here
   as the thing to check, not pre-decided either way.
6. **Known extraction hazard — `learning` ↔ `assessment` split.**
   `learning.assignment_submissions.grade_id` carries a real, enforced
   database foreign key into `assessment.grades` (added via `ALTER TABLE` in
   `V41__assessment_grades.sql`, after `assessment.grades` exists — see
   [migration-order.md](migration-order.md) for why it can't be created
   inline with the column). If Learning and Assessment are ever split into
   separately-deployed services with separate databases (both are candidates
   above), this FK **cannot** be carried forward as a database constraint —
   cross-database FKs don't exist. Before that split, this specific
   constraint must be replaced with an application-level or
   eventually-consistent check, most naturally driven off the already-catalogued
   `GradePublished` event (see kafka-event-catalog.md), the same way
   `record.student_academic_records` already consumes grade outcomes without
   an FK. Flagged here explicitly so Day 16's coupling measurement doesn't
   discover this FK cold at extraction time — it's the one place in the
   schema where Learning and Assessment are coupled at the database level,
   not just the event level.
