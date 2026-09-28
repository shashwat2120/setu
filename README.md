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
| Language | Java 21 (virtual threads) |
| Backend | Spring Boot 4.x, Spring Modulith |
| Security | Spring Security 7 — passkeys/WebAuthn, Argon2id fallback |
| AI | Spring AI 2.x + Ollama (local) |
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
| SUPER_ADMIN | — | role only, no UI |
| COLLEGE_ADMIN | Registrar operations | designed |
| FACULTY | Live attendance + student risk detail | designed |
| STUDENT | Student overview | designed |
| PLACEMENT_OFFICER | — | role only, no UI |
| GUARDIAN | Reduced read-only student view | planned |

Roles are not authorization. Every request evaluates
role + tenant + resource + action + workflow state + ownership.

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
├── backend/     Spring Boot modular monolith
├── frontend/    React + TypeScript + Vite
├── infra/
│   ├── docker/  Compose files
│   └── db/      Flyway migrations
└── docs/        ERD, ADRs, state machines
```

## Design

Visual identity: a "permanent record / ledger" system — deep ink navy, warm
paper, brass accent, Fraunces (display) + Public Sans (body) + IBM Plex Mono
(data). Real photography, not illustration. Tokens live in
`frontend/src/styles/tokens.css`; the landing page and its shared `Reveal`/
`Figure` components in `frontend/src` are the reference implementation — reuse
them for new screens rather than introducing a second visual language.
