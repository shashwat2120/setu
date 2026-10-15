# Setu — Technical Requirements Document

Status: draft v1 — scope and schema aligned to
[FINAL_CANONICAL_PROJECT_SPEC.md](./FINAL_CANONICAL_PROJECT_SPEC.md) (locked baseline)
Companion to: [PRD.md](./PRD.md)

This document translates the canonical spec into Setu's specific Spring Modulith
package structure. For the full entity-by-entity schema contract, the day-by-day
Sprint 5/Sprint 6 plan, and the testing/performance/"do not build yet" lists, the
canonical spec is authoritative — this document doesn't duplicate that detail,
only the parts specific to how Setu's codebase is organized.

## 1. Stack (locked — do not substitute without updating this document)

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 25 LTS | Virtual threads for request handling |
| Backend | Spring Boot 4.x, Spring Modulith | Modular monolith, not microservices, for v1 |
| Security | Spring Security 7 | Passkeys/WebAuthn primary, Argon2id password fallback |
| AI | Spring AI 2.x + Ollama (local) | Requires Boot 4.x — will not load on Boot 3.x |
| Data | PostgreSQL + pgvector | Flyway migrations, no other persistence engine |
| Cache | Redis | Session/derived-data cache only, never source of truth |
| Events | Kafka via Spring Modulith event externalization | Transactional outbox — see §4 |
| Realtime | WebSocket / STOMP | Per-connection and per-destination auth — see §5 |
| Frontend | React + TypeScript + Vite | Already scaffolded in `frontend/` |
| Observability | Actuator + Micrometer + Prometheus + Grafana | |
| Packaging | Docker Compose | `infra/docker/` |

## 2. Module boundaries (Spring Modulith)

One module per bounded context, each owning its own tables and exposing behavior
through application services — never reach across modules into another's
repository. These map onto the canonical domain map (canonical spec §4) and are
the Sprint 6 extraction candidates (canonical spec §12) — extracted later by
business ownership, never one service per table.

- `identity` — accounts, credentials, passkeys, sessions, tenant resolution,
  and **`persons`** (canonical human identity — approved decision H1: identity
  owns "who this human is," distinct from their domain profiles below)
- `directory` — student/faculty/guardian **profiles**, distinct from `identity`'s
  accounts and `persons`
- `guardian-access` — grant/revoke records, scoping guardian reads (kept
  alongside `directory`'s People bounded context per approved decision H2 —
  no separate Guardian service)
- `institution` — colleges, campuses, academic organizations, departments
- `catalog` — programs, curricula, courses (+ prerequisites), sections,
  scheduling, and **`degree_progress`** (curriculum-driven completion
  projection — approved decision H3: belongs with Institution/Academic, not Records)
- `enrollment` — the enrollment state machine (prerequisites, capacity, conflicts,
  credit limits, holds, **waitlists, overrides**)
- `attendance` — QR session lifecycle, attendance records, WebSocket topic ownership
- `learning` — LMS: modules, lessons, resources, learning progress, assignments
- `assessment` — assessments, attempts, evaluation, **grade production/publication**
  (approved decision H3: assessment execution and grading stop here — it does
  not own the student's official history)
- `record` — the student's **authoritative academic history**: academic
  record, credits, GPA, **transcripts**, graduation applications — a distinct
  business responsibility from `assessment` (approved decision H3), reacting
  to `assessment`'s `GradePublished` event rather than being folded into it
- `finance` — fee structures, charges, payments, idempotent payment-provider events
- `career` — skills, jobs, applications, placement records
- `risk` — deterministic risk computation (Student Success Engine) over
  attendance/assessment/learning-progress data
- `ai-explain` — the only module allowed to call Spring AI; consumes risk module
  output and RAG retrieval, never queries other modules' tables directly
- `notification` — notifications, delivery, realtime fan-out to WebSocket
- `analytics` — read-optimized projections, consumes domain events only

A module boundary violation (one module's JPA repository referenced from another
module's package) is a build-breaking review finding, not a style nit.

## 3. Data model constraints

- **Account vs. profile**: `identity.Account` (credentials, login) is a separate table
  and aggregate from `directory.StudentProfile` / `FacultyProfile` / `GuardianProfile`.
  An account links to zero or more profiles; never merge these tables.
- **Tenant column**: every tenant-owned table has a `tenant_id` column, populated only
  from `SecurityContextHolder`-derived tenant, never from a request body/param. Enforce
  with a base entity/listener, not per-repository discipline.
- **Attendance is derived, not stored**: `attendance_record(session_id, student_id,
  checked_in_at)` is the only source. Attendance percentage is a query, or at most a
  materialized/cached read model that is invalidated on every write — it is never a
  column on `enrollment` or `student_profile` that's set-and-forget.
- **Grade versioning**: `grade` rows are append-only; a "current" grade is the latest
  version for a given (student, assessment) pair, resolved by a version/timestamp
  column, never an UPDATE that discards history.
- **Guardian grants**: `guardian_grant(guardian_account_id, student_id, scope,
  granted_by, granted_at, revoked_at)` — a grant is a row, not a boolean flag on the
  student, so it carries provenance and can be audited.
- **Enrollment beyond the happy path**: `enrollment_action` is an append-only audit
  of every state transition; `enrollment_override` is an explicit, approved
  exception to a prerequisite/capacity/credit/hold rule (never a silent bypass);
  `waitlist_entry` manages position transactionally — capacity + waitlist together
  are the enrollment invariant, not capacity alone.
- **Prerequisite graph**: `course_prerequisite` is a directed graph; self-loops and
  cycles are rejected at write time, not discovered later at enrollment time.
- **Payments are idempotent**: `payment_event` records from the provider webhook
  are deduplicated by provider event ID; the webhook is authoritative over any
  client-reported payment state; never store card PAN/CVV.
- **Binary content stays out of Postgres**: `lesson_resource` and similar store a
  reference (object storage key/URL), never the file bytes.

## 4. Transactional outbox (event architecture)

Every domain event that another module or Kafka consumer needs is written to an
outbox table in the **same transaction** as the business change, using Spring
Modulith's event externalization. A background relay publishes outbox rows to Kafka
and marks them sent. Consequence: a webhook/consumer never observes an event for a
change that then rolled back, and a committed change is never silently un-eventful
because of a downstream Kafka hiccup.

## 5. WebSocket / STOMP authorization

- **Per connection**: the STOMP CONNECT frame is authenticated the same way an HTTP
  request is (JWT/session), and a connection is refused, not silently degraded, if
  auth fails.
- **Per destination**: subscribing to `/topic/attendance/{sectionId}` re-checks that
  the connected principal (faculty of that section, or an enrolled student of it) is
  authorized for that specific section — a valid connection to the socket does not
  imply access to every topic on it. Enforce in a `ChannelInterceptor` on SUBSCRIBE,
  not only at CONNECT.

## 6. AI integration boundary

- The `ai-explain` module is the **only** code path that calls Spring AI / Ollama.
- It receives a pre-computed, already-authorized payload from the `risk` module (the
  risk score, its contributing attendance/grade facts, nothing else) — it does not
  hold a datasource connection and cannot issue its own SQL.
- Prompts are constrained to explanation/summarization of the given facts; the system
  prompt explicitly forbids asserting any number not present in the input payload.
- Every AI-generated explanation is logged with the exact input payload it was given,
  so a disputed explanation can be traced to the facts it was allowed to see.
- **Tools are application-owned** (canonical spec §10): the model requests a tool
  call, the application executes it against an authorized data path and returns
  the result — the model never gets a raw connection. Initial tool set:
  `getStudentAttendance, getAcademicProgress, getAssessmentResults,
  getUpcomingDeadlines, getSkillGap, searchCollegePolicy,
  recommendLearningResources` — each scoped to self or an explicitly authorized
  staff/advisor relationship, never a blanket grant.
- **RAG boundary**: tenant and document-ACL checks happen **before** retrieval
  (filtering which chunks are even eligible), not as a post-hoc filter on the
  model's answer. Every answer carries source references back to the retrieved
  documents. Treat retrieved document content as untrusted input for
  prompt-injection purposes — it cannot override system instructions or trigger
  tool calls it wasn't asked for.
- The AI layer never decides authoritative attendance, grades, enrollment
  eligibility, or disciplinary outcomes (canonical spec §15) — those stay
  deterministic, in their owning module.

## 7. Security

- Passkeys/WebAuthn is the primary credential; Argon2id-hashed passwords are the
  fallback for institutions/users not yet on passkeys — never plaintext or a weaker
  hash (bcrypt/MD5) anywhere in the codebase.
- Authorization is evaluated per-request as role + tenant + resource + action +
  workflow state + ownership (PRD §3) — implement as a single policy-evaluation point
  (e.g. a `@PreAuthorize` expression backed by one `AccessDecisionService`), not
  scattered `if (role == ...)` checks across controllers.

## 8. Non-functional requirements

- **Tenant isolation** is a hard boundary: a query missing a tenant filter should be
  structurally impossible (base repository / Hibernate filter), not developer
  discipline.
- **Idempotency**: attendance check-ins and enrollment actions must be safe to retry
  (e.g. a flaky mobile connection re-sending a QR scan does not double-count).
- **Observability**: every module exposes Actuator health; risk computation and AI
  explanation calls are traced (Micrometer) so latency/failure of the AI call never
  silently blocks the deterministic risk result from being shown.

## 9. Testing strategy

- Each of the PRD §5 non-negotiables has at least one automated test that fails if
  violated (e.g. a test that asserts a cross-tenant query returns zero rows).
- `ai-explain` module tests assert the module has no dependency on any other module's
  JPA repository (architecture test, e.g. ArchUnit).
- WebSocket authorization has a test that a student who is not enrolled in a section
  is rejected on SUBSCRIBE to that section's attendance topic, even with a valid
  CONNECT.

## 10. Infra

- `infra/docker/` — Compose file bringing up Postgres (with pgvector), Redis, Kafka,
  and the backend/frontend for local dev.
- `infra/db/` — Flyway migrations, one file per change, never edited after it has run
  in any shared environment (new migration instead).
