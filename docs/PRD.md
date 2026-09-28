# Setu — Product Requirements Document

Status: draft v1 — scope aligned to [FINAL_CANONICAL_PROJECT_SPEC.md](./FINAL_CANONICAL_PROJECT_SPEC.md) (locked baseline)
Owner: Shashwat Mishra

## 1. Problem

Colleges run admissions, attendance, grading, and risk tracking across disconnected
spreadsheets, legacy SIS software, and manual processes. No single system tells a
registrar, a faculty member, and a student the same, current, trustworthy answer to
"is this student okay." Setu is a college operating system that gives every role one
shared, authoritative view — built so that what the system says is always backed by a
record, never a guess.

## 2. Core principle (non-negotiable, drives every decision below)

PostgreSQL is the source of truth for institutional records. Deterministic rules
calculate authoritative state (eligibility, grades, credits, risk). AI explains,
summarizes, and recommends — it never invents an academic fact and never bypasses
authorization. If a feature requires the AI to assert something not derivable from
the database, the feature is out of scope until the underlying data model supports it.

## 3. Users and roles

| Role | Who they are | What they need from Setu |
|---|---|---|
| SUPER_ADMIN | Platform operator | Tenant provisioning. No UI in v1. |
| COLLEGE_ADMIN | Registrar staff | Run enrollment, manage sections/capacity, resolve holds, see institution-wide risk. |
| FACULTY | Teaching staff | Take attendance in seconds per class, see which of their students need attention and why. |
| STUDENT | Enrolled student | See their own attendance, grades, and risk status in plain language. |
| PLACEMENT_OFFICER | Careers staff | Role reserved; no UI in v1. |
| GUARDIAN | Parent/guardian | Reduced read-only visibility into one student, granted and revocable by that student or the institution. |

Roles are not authorization. Every request is evaluated on
**role + tenant + resource + action + workflow state + ownership** — a student can
always see their own record; a faculty member can only see students in their own
sections; a guardian can only see what was explicitly granted.

## 4. Scope

Full product scope — nothing is cut for v1. Any earlier draft of this document or
the README that excluded LMS, finance, career/placement, transcripts, waitlists,
overrides, or RAG is superseded; see canonical spec §0/§17.

### 4.1 Full scope, delivered in two phases (not two feature sets)

- Identity & tenancy — accounts, passkey/password login, tenant isolation
- Institution — colleges, campuses, academic organizations, departments
- People — students, faculty, guardians, guardian delegated access
- Academic — programs, curriculum versions, courses (with prerequisites), sections
- Operations — classes/cohorts, enrollment (prerequisites, capacity, conflicts,
  credit limits, holds, **waitlists, overrides**), timetable
- QR attendance over WebSocket — faculty opens a session, students check in live,
  attendance percentage is always calculated from records, never stored as a field
- LMS — modules, lessons, resources, learning progress, assignments
- Assessment & grade — versioned grades, published grades never silently
  overwritten, academic record, **transcripts**, degree progress
- **Finance** — fee structures, charges, payments (idempotent provider integration)
- **Career & placement** — skills, jobs, applications, placement records
- Risk engine (Student Success Engine) — deterministic rules over
  attendance/assessment/learning-progress trends produce a risk signal + ranked
  factors + recommended interventions
- AI explains risk — given a computed risk signal and its supporting records, AI
  produces a plain-language explanation; it does not compute the risk itself
- **RAG over college-controlled documents** — permission-aware retrieval,
  tenant/document-ACL checked before retrieval, answers carry source references
- Notifications & realtime delivery; analytics, audit, and event infrastructure

### 4.2 Phasing (Sprint 5 → Sprint 6, see canonical spec §11–12)

**Sprint 5 (15 days)** builds everything above as one Spring Modulith monolith.
**Sprint 6 (15 days)** extracts justified bounded contexts into microservices
(Identity, Academic, Learning, Assessment, Attendance, Student Success, Career,
Finance, Notification/Realtime, AI, Analytics) behind an API Gateway — an
extraction of the Sprint 5 codebase, not a rewrite, and not "one service per
table" (non-negotiable — canonical spec §3).

## 5. Non-negotiables (product-level; the TRD restates these as technical constraints)

1. A user account is a separate entity from a student/faculty/guardian profile.
2. Every tenant-owned row carries its tenant, derived from the security context —
   never accepted from the client.
3. Course is the catalog entity; section is the scheduled, time-bound offering of it.
4. Enrollment is a stateful transaction: prerequisites, capacity, time conflicts, and
   holds are all checked before it succeeds.
5. Attendance percentage is always calculated from attendance records at read time —
   never cached as a single stored field that can drift from the truth.
6. Grades are versioned. A published grade is never overwritten in place; a
   correction creates a new version with a visible history.
7. Guardian access is an explicit, scoped grant tied to one student, and it is
   revocable at any time by the student or the institution.
8. A business transaction and its outbox event commit atomically — no event is ever
   published for a change that didn't actually happen, and no change happens silently.
9. WebSocket authorization is checked both at connection time and again per message
   destination — a valid connection does not imply access to every topic on it.
10. The AI model never receives unrestricted database access. It is given only the
    specific, already-computed facts relevant to the explanation it's producing.

## 6. Role dashboards (what ships)

| Role | Dashboard | v1 status |
|---|---|---|
| COLLEGE_ADMIN | Registrar operations — enrollment queue, sections, institution risk view | Build |
| FACULTY | Live attendance session + per-student risk detail for their sections | Build |
| STUDENT | Personal overview — attendance, grades, risk explanation | Build |
| GUARDIAN | Reduced read-only view of one granted student | Build (reduced) |
| SUPER_ADMIN / PLACEMENT_OFFICER | — | Role only, no UI |

## 7. Success criteria for v1

- A registrar can enroll a student into a section and have every downstream
  screen (faculty roster, student overview) reflect it within one request cycle.
- A faculty member can open a live attendance session and have a student's phone
  scan reflected on the faculty screen without a manual refresh (WebSocket).
- A student whose attendance drops below a threshold sees a risk flag with an
  AI-written explanation that cites the actual attendance and grade records behind it
  — and that explanation is demonstrably wrong exactly zero times, because it is
  never allowed to state a fact the database doesn't hold.
- A guardian granted access to one student cannot see any other student, and that
  grant can be revoked and immediately takes effect.
- Every one of the 10 non-negotiables in §5 has a corresponding automated test that
  fails if it's violated.

## 8. Open questions

- Exact risk-scoring formula (attendance threshold, grade-trend weighting) — owned by
  the risk engine design, not this document; TRD/implementation should not hardcode a
  guess without sign-off.
- Institution branding/multi-tenant theming — out of scope for v1 visual design,
  which targets a single reference tenant.
