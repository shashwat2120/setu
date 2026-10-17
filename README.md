# Setu

College operating system — SIS, LMS and an AI-assisted student success engine.

Setu means "bridge": the platform connects students, faculty, guardians and
administration, and the permission model is built around exactly that.

**Core principle:** PostgreSQL is the source of truth for institutional records.
Deterministic rules calculate authoritative state (eligibility, grades, credits,
risk). AI explains, summarises and recommends — it never invents an academic
fact and never bypasses authorization.

**Authoritative spec:** [docs/FINAL_CANONICAL_PROJECT_SPEC.md](docs/FINAL_CANONICAL_PROJECT_SPEC.md)
is the locked build contract. Where anything below (or in `docs/PRD.md` /
`docs/TRD.md`) conflicts with it, the canonical spec wins.

---

## Stack (locked)

| Layer | Choice |
|---|---|
| Language | Java 25 LTS (virtual threads) |
| Backend | Spring Boot 4.x, Spring Modulith |
| Security | Spring Security 7 — passkeys/WebAuthn, Argon2id fallback |
| AI | Spring AI 2.x: Gemini as the primary provider when a key is configured, with a local Ollama model as the fallback |
| Data | PostgreSQL + pgvector, Flyway migrations |
| Cache | Redis |
| Events | Kafka via Spring Modulith event externalization (transactional outbox) |
| Realtime | WebSocket / STOMP |
| Frontend | React + TypeScript + Vite |
| Observability | Actuator + Micrometer + Prometheus + Grafana |
| Packaging | Docker Compose |

Spring AI 2.x requires Spring Boot 4.x — it cannot load in a Boot 3.x context.
Most tutorials online are still Boot 3 / Spring AI 1.x.

## Scope

Full product scope (all in — nothing cut): identity & auth, tenant/college
management, students/faculty/guardians, programs/curricula/courses/sections,
classes/cohorts & enrollment (with waitlists and overrides), timetable &
attendance, LMS & learning progress, assessments/grading/academic records/
transcripts, finance, career & placement, the Student Success Engine,
notifications & realtime delivery, AI orchestration, RAG over college-controlled
documents, and analytics/audit/event infrastructure.

Built in two phases, not cut down to a smaller demo:

- **Sprint 5 (15 days):** the full system above as a Spring Modulith monolith —
  domain/data foundation, identity/authZ, academic core, realtime learning
  (QR attendance), finance/career/success, then AI + hardening.
- **Sprint 6 (15 days):** extract justified bounded contexts (Identity, Academic,
  Learning, Assessment, Attendance, Student Success, Career, Finance,
  Notification/Realtime, AI, Analytics) into Spring Boot microservices behind an
  API Gateway — an evolution of the Sprint 5 codebase, not a rewrite.

See [docs/FINAL_CANONICAL_PROJECT_SPEC.md](docs/FINAL_CANONICAL_PROJECT_SPEC.md)
§11–12 for the day-by-day breakdown.

## Roles and dashboards

| Role | Dashboard | Status |
|---|---|---|
| SUPER_ADMIN | Colleges, roles, platform audit log | built |
| COLLEGE_ADMIN | Registrar operations: enrollment, sections, attendance (student and staff), grades, finance, career, audit log | built |
| FACULTY | Courses and lesson content, live QR attendance, assessments and grades, student success, own check-in/out | built |
| STUDENT | Courses and lessons, attendance, grades, finance, career, success, AI advisor | built |
| PLACEMENT_OFFICER | Jobs, skills, placements | built |
| GUARDIAN | Reduced read-only student view, scoped to the grant | built |

Roles are not authorization. Every request evaluates
role + tenant + resource + action + workflow state + ownership.

## Quick start (local)

Prerequisites: Java 25 LTS, a current Node.js (developed on Node 24), Docker Desktop.

```bash
# 1. Postgres (pgvector), Redis and Kafka
cd infra/docker && cp .env.example .env && docker compose up -d

# 2. Backend with demo data (see backend/README.md for why this is a test-launched bridge on this Windows machine)
cd backend
DB_PORT=5433 REDIS_PORT=6380 KAFKA_BOOTSTRAP_SERVERS=localhost:9093 ./mvnw -q test "-Dtest=LiveDevBridge#serve" -DfailIfNoTests=false

# 3. Frontend
cd frontend && npm install && npm run dev
```

Wait for `LIVE_DEV_BRIDGE_LISTENING_ON_8089` in the backend log, then open <http://localhost:5180>. The ports above are this
repo's `.env.example` defaults (5433/6380/9093, chosen so they do not clash with other local projects); change them together
if you change `infra/docker/.env`.

**Demo logins** (seed data; every account shares one password, `SetuDemo#2025!`):

| Role | Email |
|---|---|
| Super admin | `super.admin@demo.setu.local` |
| College admin | `admin.sit@demo.setu.local` |
| Faculty | `faculty.rao@demo.setu.local`, `faculty.mehta@demo.setu.local` |
| Student | `student.strong@demo.setu.local` (and `student.average`, `student.attendance.risk`, `student.assessment.risk`, `student.learning.risk`, `student.intervention`) |
| Guardian | `guardian.kapoor@demo.setu.local` |
| Placement officer | `placement.officer@demo.setu.local` |

A second college (`admin.mrd@`, `faculty.mrd@`, `student.mrd@`) exists to show tenant isolation. The full list is printed in the
backend log at startup, and the click-through is in [docs/demo-script.md](docs/demo-script.md).

## What is built

- **Identity:** passkey and Argon2id password sign-in, rotating refresh tokens, rate-limited login and password reset, and a
  password reset by emailed 6-digit code (15 minutes, one use, 5 guesses) with a server-side password policy (8 to 128 characters,
  no very common passwords or the account's own email). A completed reset signs out every existing session and cancels the
  user's other codes.
- **Academic core:** institutions, programs and curricula, courses and sections, enrollment with waitlists and overrides,
  credit-weighted GPA, degree requirements.
- **Attendance:** rotating signed QR attendance over STOMP; faculty (staff) attendance with check-in/out, a versioned policy
  (shift, late grace, half-day and short-hours thresholds) and regularisation requests for forgotten days. Percentages are
  computed from records, never stored.
- **Learning:** modules, lessons and assignments. A lesson links to a real video (a YouTube link plays inside the lesson page)
  and carries attached documents and links. Setu stores **references only**: `https://`, `ref://` or `s3://` addresses, never
  the files themselves.
- **Assessment, records, finance, career:** versioned grades, transcripts, fee accounts with idempotent payment webhooks, jobs
  and placements.
- **Student Success Engine and AI advisor:** a deterministic, reproducible risk score; an advisor that answers only through
  application-owned, authorization-checked tools and cites its sources. College policy questions use retrieval over pgvector
  embeddings (Gemini first) with the tenant and document-access check applied before ranking (see [backend/README.md](backend/README.md#college-policy-search-rag)).
- **Platform:** transactional outbox to Kafka, per-module Flyway migrations, an audit log, Actuator health and Modulith endpoints, and Micrometer metrics scraped by Prometheus and drawn on a provisioned Grafana dashboard (see [infra/docker/README.md](infra/docker/README.md#observability)).

- **Kafka consumer:** the notification module consumes the outbox events (grade published, payments, placement, recommendation, intervention,
  admission) and writes in-app notifications, once per event id, with retry and a dead-letter topic (see
  [docs/kafka-event-catalog.md](docs/kafka-event-catalog.md#consumers-that-exist)).

Sprint 6 (service extraction) has not started: the system is still one Spring Modulith application.

## Configuration

Secrets and local overrides live in the git-ignored `backend/.env.local`; nothing secret is committed.

| Variable | Purpose |
|---|---|
| `AI_PROVIDER` | `google-genai` (default: Gemini primary, Ollama as the fallback; with no `GEMINI_API_KEY` the run starts on Ollama instead) or `ollama` to force the local model |
| `EMBEDDING_PROVIDER` | Policy-search embeddings: `auto` (follows the chat provider), `gemini`, `ollama` or `local`: see [backend/README.md](backend/README.md#college-policy-search-rag) |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Gemini credentials and model. Pick a model that actually answers for your key: see [backend/README.md](backend/README.md#ai-advisor) |
| `AI_PRIMARY_TIMEOUT_SECONDS` | How long Gemini may take before the local fallback is tried (default 25) |
| `SMTP_USER`, `SMTP_PASSWORD` | Real password-reset email delivery over SMTP, for example Gmail with an app password. Without them the email is not sent: see [backend/README.md](backend/README.md#password-reset-and-email) |
| `METRICS_SCRAPE_TOKEN` | The one token Prometheus presents to read `/actuator/prometheus`. Create it with `infra/docker/observability/init-scrape-token.ps1`; blank keeps the endpoint closed |
| `EMAIL_PREVIEW_ENABLED` | Also write each reset email to `backend/target/demo-email-outbox` for local inspection |

## Testing

```bash
cd backend && ./mvnw test      # the full suite (hundreds of tests); needs the Docker stack up and a fresh database
cd frontend && npx tsc -b && npx oxlint && npx vite build
```

The seeder verification tests need an empty database, so reset it first with `docker compose down -v && docker compose up -d`.

## Non-negotiables

1. User account is separate from student/faculty/guardian profile.
2. Every tenant-owned row carries its tenant, derived from the security context —
   never from the client.
3. Course is the catalog entity; section is the scheduled offering.
4. Enrollment is a stateful transaction: prerequisites, capacity, conflicts, holds.
5. Attendance percentage is calculated from records, never stored as one field.
6. Grades are versioned; published grades are never silently overwritten.
7. Guardian access is an explicit, scoped, revocable grant.
8. The business transaction and the outbox event commit atomically.
9. WebSocket authorization is per connection AND per destination.
10. The AI model never gets unrestricted database access.

## Layout

```
setu/
├── backend/     Spring Boot modular monolith (see backend/README.md)
├── frontend/    React + TypeScript + Vite (see frontend/README.md)
├── infra/
│   ├── docker/  Compose files (see infra/docker/README.md)
│   └── db/      Flyway migrations (see infra/db/README.md)
└── docs/        spec, ERD, state machines, Kafka catalog, demo script
```

## Design

Visual identity: a "permanent record / ledger" system — deep ink navy, warm
paper, brass accent, Fraunces (display) + Public Sans (body) + IBM Plex Mono
(data). Real photography, not illustration. Tokens live in
`frontend/src/styles/tokens.css`; the landing page and its shared `Reveal`/
`Figure` components in `frontend/src` are the reference implementation — reuse
them for new screens rather than introducing a second visual language.
