# Setu — Agent playbook

This file is for AI coding agents (Claude Code and equivalents) working in this
repository. Read [docs/FINAL_CANONICAL_PROJECT_SPEC.md](docs/FINAL_CANONICAL_PROJECT_SPEC.md)
first — it is the locked, authoritative build contract. [docs/PRD.md](docs/PRD.md)
and [docs/TRD.md](docs/TRD.md) are aligned to it and add Setu-specific detail
(module package names, dashboard specifics); if anything anywhere conflicts with
the canonical spec, the canonical spec wins — stop and flag the conflict rather
than silently picking one (canonical spec §17.11).

**Full scope is in. Nothing is cut.** LMS, finance, career/placement, transcripts,
waitlists, enrollment overrides, guardian delegated access, the Student Success
Engine, and RAG over college documents are all in scope for Sprint 5 — not stubs,
not "if time allows." An earlier draft of this repo's docs trimmed scope to a
smaller "spine"; that draft is superseded (canonical spec §0).

## Standing rules for every task in this repo

1. **Never contradict the canonical spec's locked stack** (§2) without the user
   explicitly approving a change to that document first.
2. **Non-negotiables (canonical spec §3) are not suggestions.** If a task as
   described would violate one, stop and flag it rather than implementing the
   shortcut.
3. **Module boundaries are enforced, not aspirational** (TRD §2, canonical spec
   §4). A change that reads another module's repository/entity directly is wrong
   even if it "works" — it's exactly the coupling Sprint 6 extraction depends on
   not existing.
4. **The `ai-explain` module never gets a datasource**, and RAG retrieval always
   applies tenant/document-ACL filtering *before* retrieval, not after (canonical
   spec §10).
5. **Design system**: the frontend uses the "ledger" visual language already built
   in `frontend/src` — deep ink navy / warm paper / brass accent, Fraunces + Public
   Sans + IBM Plex Mono, real photography, GSAP-driven scroll motion via the
   shared `Reveal` and `Figure` components. Match it; don't introduce a second
   visual language for new screens.
6. **Real content only.** No lorem ipsum, no placeholder numbers presented as if
   real. Mark example/seed data explicitly as an example.
7. **Every non-negotiable and every entity's stated invariant gets a test**
   (canonical spec §13) in the same change that implements the behavior it
   constrains — not a follow-up.
8. **Don't generate entities before the Definition of Done gates in canonical spec
   §18 are actually met** for that area — an ERD with unresolved cardinality
   contradictions, or a state machine that hasn't been written down, is not ready
   to become JPA entities yet.
9. **Before ending a task that touches the frontend**, run it in the browser and
   check console/network for errors.
10. **Commit and PR discipline**: small, reviewable changes per module/feature —
    don't land a whole day's scope (let alone a whole phase) in one change.
11. **If scope conflicts reappear, stop and flag them explicitly** (canonical spec
    §17.11) — don't guess which document wins.

## How to use the prompts below

Numbered to match the canonical spec's own day numbering (§11–12) so progress is
directly traceable against it. Paste a prompt to Claude Code as-is to start that
day's work; each assumes prior days landed. Don't skip ahead — attendance depends
on enrollment, risk depends on attendance + assessment + learning progress, AI
explanation depends on risk, and Sprint 6 depends on all of Sprint 5's module
boundaries actually holding up under real coupling.

### Phase 0 — infra foundation (precedes Day 1)

**0.1 — Docker Compose + Flyway skeleton** — done. See `infra/docker/`, `infra/db/`.

**0.2 — Backend project skeleton**
> Scaffold `backend/` as a Spring Boot 4.x + Spring Modulith project (Java 21,
> Maven or Gradle — pick one and note the choice), with empty module packages for
> every bounded context in TRD.md §2 (identity, directory, guardian-access,
> institution, catalog, enrollment, attendance, learning, assessment, record,
> finance, career, risk, ai-explain, notification, analytics), a Spring Modulith
> architecture test verifying module boundaries, and Flyway wired to `infra/db/`
> per its README. No business logic yet — scaffolding only.

### Sprint 5A — Days 1–2: domain + data foundation

**5A.1 — Business glossary, context map, ERD**
> Before writing any entity: produce a business glossary (one line per term:
> college, campus, department, program, curriculum, period, class, course,
> section, enrollment, academic record — canonical spec §11 5A), a context map
> showing which module owns which entities (canonical spec §4), and a final ERD
> covering every entity in canonical spec §5 with PK/FK, cardinality, unique
> constraints, and indexes. Write this as `docs/ERD.md`. Flag any cardinality
> contradiction rather than resolving it by guessing — this is the exit gate for
> 5A (canonical spec §11).

**5A.2 — State machines**
> Document the state machine for every stateful entity before it's implemented:
> enrollment (requested → enrolled → dropped, with waitlist and hold states),
> attendance session (open → closed), assessment (draft → open → closed →
> published), grade (draft → published → corrected), guardian grant (granted →
> revoked). Write as `docs/state-machines.md`. This is part of the canonical spec
> §18 Definition of Done — don't start entities until it exists.

**5A.3 — Migrations**
> Turn the ERD from 5A.1 into Flyway migrations under `infra/db/migrations/`,
> schema-per-module (canonical spec §2), with optimistic-locking `version`
> columns on every mutable aggregate. Test a clean install against the Docker
> Compose Postgres.

### Sprint 5B — Days 3–4: identity + authorization

**5B.1 — Identity & tenancy**
> Implement the `identity` module: `Account` entity separate from any profile
> table, tenant resolution from the security context, Spring Security 7 passkey/
> WebAuthn registration + login, Argon2id password fallback (rate-limited,
> breached-password policy), session/refresh-token rotation (refresh token stored
> only as a hash, rotated on every use, Secure HttpOnly SameSite cookie — never
> localStorage). Benchmark Argon2id parameters on the actual dev machine.

**5B.2 — Authorization pipeline + IDOR tests**
> Implement the single authorization decision pipeline (canonical spec §6):
> authenticate → tenant resolution → permission → resource ownership/assignment →
> workflow/state → explicit grant if applicable → allow/deny → audit. Add IDOR
> tests and the non-negotiable tests for account/profile separation and
> tenant-never-from-client (canonical spec §3).

### Sprint 5C — Days 5–8: academic core

**5C.1 — Institution + people + guardians**
> Implement `institution` (colleges, campuses, academic organizations,
> departments), `directory` (student/faculty/guardian profiles linked to
> `identity.Account`), and `guardian-access` (`guardian_grant` as an auditable
> row, not a boolean — explicit, scoped, revocable, revocation takes immediate
> effect).

**5C.2 — Curriculum + courses + sections**
> Implement `catalog`: programs, curriculum versions + requirements, courses with
> a prerequisite graph (reject self-loops/cycles at write time), sections with
> room/building scheduling (room capacity ≥ section capacity), faculty
> assignments.

**5C.3 — Enrollment (full state machine)**
> Implement `enrollment` per the full invariant chain in canonical spec §5: active
> program → period open → prerequisite satisfied → no duplicate enrollment → no
> schedule conflict → capacity available OR waitlist → credit limit valid → no
> blocking hold → optional override → commit. Include `enrollment_action`
> (append-only audit), `enrollment_override` (explicit approved exception, never
> silent), `waitlist_entry` (transactional position). Add the last-seat-race
> concurrency test (canonical spec §13).

### Sprint 5D — Days 9–11: realtime learning

**5D.1 — QR attendance over WebSocket**
> Implement `attendance`: session lifecycle, rotating signed QR (short-lived,
> never exposes student PII), authenticated scan validation (token + tenant +
> expiry + enrollment + duplicate-protection), STOMP topic per section with the
> per-connection AND per-destination authorization from TRD.md §5. Attendance
> percentage computed from records at read time, never stored as a column. Wire
> the transactional outbox for `AttendanceRecorded`.

**5D.2 — LMS**
> Implement `learning`: modules, lessons, resources (reference, not binary
> storage), learning progress, assignments + submissions, access limited to
> enrolled students / authorized faculty.

**5D.3 — Assessment & grading**
> Implement `assessment`: assessment + components + questions, attempts +
> responses, versioned append-only grades, publish-creates-new-version (never
> overwrite). Implement `record`: student academic record, credits, GPA,
> transcripts (generated from authoritative records), degree progress.

### Sprint 5E — Days 12–13: finance + career + success

**5E.1 — Finance**
> Implement `finance`: fee structures/components, student charges/accounts,
> payments with idempotent provider webhook handling (webhook is authoritative,
> dedupe by provider event ID, no card PAN/CVV storage), receipts.

**5E.2 — Career**
> Implement `career`: skills/skill categories, student skill profile, jobs, job
> skills, applications, placement records.

**5E.3 — Risk engine (Student Success Engine)**
> Implement `risk`: feature pipeline (attendance trend, assessment trend, missed
> assignments, learning-progress completion vs. expected, engagement trend) →
> versioned deterministic rule/model → risk score + band + ranked, evidenced
> factors → recommendation/intervention catalog. The score must be reproducible
> from stored inputs — store rule version, feature snapshot hash, calculation
> time, and factor evidence IDs. This is the exit gate for 5E: risk output is
> reproducible.

### Sprint 5F — Days 14–15: AI + platform hardening

**5F.1 — AI explains risk + RAG**
> Implement `ai-explain` per TRD.md §6 and canonical spec §10: Spring AI +
> Ollama, the application-owned tool set (`getStudentAttendance`,
> `getAcademicProgress`, `getAssessmentResults`, `getUpcomingDeadlines`,
> `getSkillGap`, `searchCollegePolicy`, `recommendLearningResources`), each
> scoped to self or an authorized relationship. RAG over college policy documents
> with tenant/ACL filtering before retrieval and source references in every
> answer. Structured-output validation before anything is persisted or acted on.
> Log every explanation's exact input payload. Add the architecture test that
> `ai-explain` has no dependency on any other module's repository, and the
> prompt-injection resistance test treating retrieved document content as
> untrusted.

**5F.2 — Platform hardening**
> Wire the remaining transactional outbox events (canonical spec §8 event list),
> Redis usage (rate limiting, session/denylist acceleration, QR state, tenant-aware
> dashboard cache — never as source of truth), Actuator + Micrometer +
> Prometheus + Grafana, and the full non-negotiable test audit (canonical spec
> §13): go through every non-negotiable and every entity invariant, confirm a
> passing automated test exists, write the missing ones. Docker Compose brings up
> the whole stack; seed realistic demo data explicitly marked as seed data.

### Frontend dashboards (alongside 5C–5F, once each module has an API)

The landing page and its shared components (`Reveal`, `Figure`, design tokens)
already exist — reuse them.

**F.1 — College admin dashboard** — registrar ops (enrollment queue incl.
waitlist/overrides, section/capacity, institution-wide risk view).

**F.2 — Faculty dashboard** — live attendance session + per-section risk detail.

**F.3 — Student dashboard** — attendance, grades, transcript, AI risk explanation
in plain language, without implying more certainty than the explanation has.

**F.4 — Guardian view** — reduced, read-only, scoped exactly to what the grant
allows; review the query against grant scope carefully, this is the screen most
likely to leak more than it should.

### Sprint 6A — Days 16–22: service extraction begins

**6A.1 — Coupling audit**
> Measure actual module coupling from the Sprint 5 codebase (not the candidate
> list in canonical spec §12 taken on faith) — map every table to exactly one
> owner, freeze inter-module contracts, define event ownership. Write
> `docs/extraction-audit.md`. This validates or revises the candidate service
> boundaries before any extraction starts.

**6A.2 — Identity Service extraction**
> Extract `identity` (users/roles/permissions/sessions/passkeys) into its own
> Spring Boot service: JWT issuer + JWK publishing, refresh rotation/revocation,
> contract tests against the gateway.

**6A.3 — Academic Service extraction**
> Extract `institution` + `catalog` + `enrollment` into an Academic service:
> command/query API, domain events published, all cross-service DB access removed
> (read models/projections instead).

**6A.4 — Gateway, discovery, config**
> Spring Cloud Gateway (routes, JWT verification, rate limiting, correlation
> IDs, central error envelope), a discovery mechanism (health-aware routing), a
> config server (secrets outside the repo, local/demo profiles).

**6A.5 — Learning, Assessment, Attendance extraction**
> Extract `learning`, `assessment`, `attendance` into their own services with
> event consumers, read models, and live WebSocket routing through the gateway.
> Concurrency validation (duplicate scan, concurrent grade update) against the
> distributed version.

### Sprint 6B — Days 23–27: distributed behavior

**6B.1 — Kafka contracts**
> Version every event schema (canonical spec §8 fields), add idempotency
> keys + a processed-event table per consumer, retry + dead-letter queue,
> correlation/causation IDs threaded through.

**6B.2 — Data ownership + resilience**
> Confirm one owner per table (no cross-service joins — read models instead),
> migration scripts from the monolith snapshot. Add OpenFeign only where a
> synchronous call genuinely can't be async, wrapped in Resilience4j
> (timeouts/circuit breakers/bulkheads) with **honest** fallbacks — never fake
> data standing in for a real answer.

**6B.3 — AI + Realtime service extraction**
> Extract `ai-explain` (AI Service) and `notification` (Notification/Realtime
> Service): async AI jobs with queued/running/completed status events, the same
> tool-authorization and RAG boundaries as the monolith version, secure tool
> boundary preserved across the network hop.

### Sprint 6C — Days 28–30: enterprise hardening

**6C.1 — Observability**
> OpenTelemetry traces, Prometheus metrics, Grafana dashboards, Loki/Tempo —
> correlate logs and traces across every extracted service.

**6C.2 — Failure/security/performance pass**
> Service-unavailable tests, Kafka consumer retry/DLQ tests, JWT/passkey/session
> tests, IDOR/tenant-isolation tests (now across service boundaries too),
> load/concurrency checks against the demo validation targets (canonical spec
> §14).

**6C.3 — Final demo**
> The full business flow end to end (canonical spec §16): passkey login →
> academic dashboard → live QR attendance → WebSocket update → assessment →
> grade event → risk recalculation → advisor alert → AI evidence-grounded
> explanation → Grafana → architecture walkthrough showing the same flow now
> distributed across services. Document known limitations and roadmap.

## Before any of the above: "do not build yet"

Per canonical spec §15 — none of these belong in Sprint 5 or Sprint 6 regardless
of how any individual prompt above is read: Kubernetes; one microservice per
table; full event sourcing; unlimited AI agents; raw LLM DB access; a heavy search
cluster on day 1; a video transcoding pipeline; WebRTC classroom infrastructure;
permanent binary storage in PostgreSQL; a guardian "master switch"; a hard-coded
semester-only academic period model; AI deciding authoritative attendance, grades,
enrollment eligibility, or discipline; claiming blanket exactly-once delivery.
