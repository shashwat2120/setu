# AI-Powered Enterprise College Management Platform — Canonical Project Specification v1.0

Sprint 5 + Sprint 6 — Authoritative Build Contract

**Status: LOCKED BASELINE**

This document exists to eliminate contradictions between README, PRD, agent notes and the
Master Blueprint before implementation. Source: `AI_Enterprise_College_Platform_Sprint5_Sprint6_Master_Blueprint.pdf`
and `FINAL_CANONICAL_PROJECT_SPEC.pdf`, both supplied by the project owner.

## 0. Authority rule — read this first

Current project: AI-Powered Enterprise College Management Platform (Setu) — a
multi-tenant College/SIS + LMS + Student Success platform.

**Any older README, PRD, agent note or draft that describes a CMS project, or
excludes LMS/finance/career/transcripts/waitlists/overrides/RAG, is outdated and
must not govern implementation.**

Sprint 5 builds the working business system as a strict modular monolith. Sprint 6
extracts justified bounded contexts into microservices. Sprint 6 is an evolution,
not a rewrite.

## 1. Product scope

- Identity and authentication
- Tenant/college management
- Students, faculty and guardians
- Programs, curricula, courses and sections
- Classes/cohorts and enrollment
- Timetable and attendance
- LMS and learning progress
- Assessments, grading and academic records
- Finance
- Career and placement
- Student Success Engine
- Notifications and realtime delivery
- AI orchestration
- RAG over college-controlled documents
- Analytics, audit and event infrastructure

Core principle: PostgreSQL stores institutional truth; deterministic rules own
authoritative decisions; AI explains, summarizes, recommends and assists but never
invents facts or bypasses authorization.

## 2. Locked technology direction

| Layer | Sprint 5 | Sprint 6 |
|---|---|---|
| Frontend | React + TypeScript + Vite | Same frontend through API Gateway |
| Backend | Spring Boot modular monolith | Spring Boot microservices |
| Data | PostgreSQL, schema-per-module | Service-owned data |
| Cache | Redis | Redis |
| Events | Kafka + transactional outbox | Kafka + versioned contracts + idempotent consumers |
| Realtime | WebSocket/STOMP | Realtime/Notification service + WebSocket/STOMP |
| AI | Spring AI + Ollama; optional provider abstraction | Dedicated AI Service |
| Observability | Actuator + Micrometer/Otel + Prometheus + Grafana | Distributed tracing/logging/metrics + Grafana/Loki/Tempo |
| Packaging | Docker Compose | Full Docker Compose |

Standards targets: OWASP ASVS 5.0.0, OWASP API Security Top 10, WCAG 2.2 AA.

## 3. Non-negotiable domain logic

- **Identity**: authentication identity is separate from student/faculty/guardian profile data.
- **Tenant isolation**: every tenant-owned record carries tenant context derived from
  trusted server-side security context; never trust arbitrary `collegeId` from React.
- **Role vs permission**: authorization evaluates user + role + permission + tenant +
  resource + ownership/assignment + workflow/state.
- **Course vs section**: course is catalog definition; section is concrete
  operational offering.
- **Class vs section**: class is cohort/group identity; section is scheduled course offering.
- **Enrollment**: stateful; validates prerequisites, capacity, conflicts, credit
  limits, holds, waitlists and overrides.
- **Attendance**: truth comes from attendance sessions and records; percentage is derived.
- **Grades**: published grades are authoritative; corrections require history and audit.
- **Guardian access**: relationships and delegated access are separate; access is
  explicit, scoped, revocable and audited.
- **AI**: no unrestricted DB/SQL access; tools are application-owned and authorized.
- **Events**: authoritative DB write and outbox event commit atomically.
- **Microservices**: extract by business ownership; never one service per table.

## 4. Canonical domain map

```
IDENTITY        users -> roles -> permissions -> sessions -> passkeys
TENANCY         colleges -> campuses -> academic organizations -> departments
ACADEMIC        programs -> curriculum versions -> courses -> sections
PEOPLE          persons -> students/faculty/guardians -> guardian delegation
OPERATIONS      classes -> enrollment -> timetable -> attendance
LEARNING        modules -> lessons -> resources -> progress -> assignments
ASSESSMENT      assessments -> attempts -> responses -> evaluation -> grades
RECORD          academic record -> credits -> GPA -> transcript -> progress -> graduation
FINANCE/CAREER/SUCCESS/PLATFORM
                charges-payments | skills-jobs-applications | risk-interventions | notifications-audit-outbox
```

## 5. Canonical schema contract

UUID primary keys for business entities. Recommended tenant-owned columns: `id,
tenant_id, created_at, created_by, updated_at, updated_by, version, status` where
applicable. Optimistic locking on mutable aggregates.

**Identity + People**: `users; roles; permissions; user_roles; sessions; passkeys;
colleges; campuses; academic_organizations; departments; persons; student_profiles;
faculty_profiles; guardians; student_guardians; guardian_access_grants;
guardian_access_permissions.`

**Academic**: `academic_years; academic_periods; programs; curricula;
curriculum_requirements; curriculum_requirement_courses; courses;
course_prerequisites; student_programs; student_academic_periods; classes;
class_students; class_faculty; course_sections; faculty_assignments; buildings;
rooms; meeting_patterns; enrollments; enrollment_actions; enrollment_overrides;
waitlist_entries.`

**Attendance/LMS/Assessment/Records**: `attendance_sessions;
attendance_qr_sessions; attendance_records; course_modules; lessons;
lesson_resources; learning_progress; assignments; assignment_submissions;
assessments; assessment_components; questions; assessment_attempts;
assessment_responses; grades; student_academic_records; transcripts;
degree_progress; graduation_applications.`

**Finance/Career/Success/Communication/Governance**:
- Finance: `fee_structures, fee_components, student_charges, student_accounts,
  payments, payment_events, receipts`
- Career: `skills, skill_categories, student_skills, jobs, job_skills,
  applications, placement_records`
- Success: `risk_scores, risk_factors, recommendations, interventions,
  intervention_actions`
- Communication: `notifications, notification_preferences, delivery_attempts`
- Governance: `audit_logs, outbox_events, idempotency_keys`, optional `api_clients`

Important invariants: enrollment uniqueness by student+section; prerequisite graph
rejects self-loops/cycles; room capacity must support section capacity; published
grades require correction history; binary media stays outside PostgreSQL; official
academic history is preserved, not deleted.

## 6. Authentication and authorization

**Passkeys/WebAuthn**: Browser → WebAuthn challenge → passkey assertion → verify
origin/RP ID/challenge/credential → principal → short-lived access token.

**Argon2id fallback**: Password → rate limit → breached-password policy → Argon2id
verification → same session/token pipeline.

Refresh tokens are stored only as hashes server-side and rotated on every refresh.
Browser refresh storage uses a Secure HttpOnly SameSite cookie — never localStorage.
Step-up authentication is required for configured privileged operations. Argon2id
parameters must be benchmarked on the actual development machine.

**Authorization pipeline**: authenticate → trusted tenant resolution → permission →
resource ownership/assignment → workflow/state → explicit grant if applicable →
allow/deny → audit.

Core roles: `SUPER_ADMIN, COLLEGE_ADMIN, FACULTY, STUDENT, PLACEMENT_OFFICER`, plus
`GUARDIAN` delegated access where implemented. Do not add roles merely for size.

## 7. Real-time architecture

```
Faculty -> create session -> validate assignment/section/meeting -> rotating QR
-> authenticated student scan -> validate token/tenant/expiry/enrollment/duplicate
-> DB transaction -> outbox AttendanceRecorded -> Kafka -> Realtime/Notification
-> WebSocket -> instructor board + student acknowledgement
```

WebSocket/STOMP must authenticate CONNECT and authorize SUBSCRIBE/SEND
destinations. Live features: live attendance, intervention alerts, assessment
status, AI job status, notification updates, admin operational counters.

## 8. Kafka + outbox

```
DB transaction: write authoritative record + write outbox event -> COMMIT
-> outbox publisher -> Kafka -> Realtime / Notification / Analytics / Search / AI
```

Every event includes `event_id, event_type, event_version, occurred_at, producer,
tenant_id, aggregate_type, aggregate_id, correlation_id, causation_id,
schema_version, payload`. Consumers must be idempotent.

## 9. Redis

Rate limiting, session/denylist acceleration, short-lived QR state, tenant-aware
dashboard caching, safe AI caching, rare short-lived distributed locks. Redis is
never the source of truth.

## 10. AI architecture

```
React -> AI Orchestrator -> AuthZ + tenant check -> Context Builder -> controlled tools
-> permission-aware RAG -> model router -> Ollama/optional provider
-> structured-output validation -> policy checks -> response/async job
```

Initial controlled tools: `getStudentAttendance, getAcademicProgress,
getAssessmentResults, getUpcomingDeadlines, getSkillGap, searchCollegePolicy,
recommendLearningResources`. Tenant and document ACL checks occur **before**
retrieval. Raw SQL and unrestricted DB access are forbidden.

## 11. Sprint 5 — 15-day checklist

| Phase | Days | Scope | Exit gate |
|---|---|---|---|
| 5A | 1–2 | Domain + data: business glossary, context map, ownership, invariants/state machines, final ERD, PK/FK/cardinality, indexes, schema-per-module, migrations, optimistic versioning | No unresolved cardinality contradictions |
| 5B | 3–4 | Identity + authorization: passkeys, Argon2id, verification/recovery, sessions, roles, permissions, tenant resolution, IDOR tests, step-up auth, audit | Tenant/resource isolation tests pass |
| 5C | 5–8 | Academic core: institution, people, guardians, programs, curriculum, courses, prerequisites, classes, sections, scheduling, enrollment, waitlist, overrides, conflicts, concurrency | End-to-end registration works |
| 5D | 9–11 | Realtime learning: QR attendance, WebSocket, LMS, assignments, assessments, grades, academic record | Phone scan → DB → Kafka → WebSocket works |
| 5E | 12–13 | Finance/career/success: charges/payments, skills/jobs/applications, skill profile, risk engine, factors, recommendations, interventions | Risk output is reproducible |
| 5F | 14–15 | AI + hardening: Spring AI, Ollama, tools, structured outputs, RAG, AI audit, Kafka/outbox, idempotency, Redis, metrics, tests, Docker, docs, seed | Demo-ready, secure, observable, documented |

## 12. Sprint 6 — microservices extraction

Candidate ownership boundaries: **Identity; Institution/Academic; Learning;
Assessment; Attendance; Student Success; Career; Finance; Notification/Realtime;
AI; Analytics; API Gateway.** Day 16 validates these against actual Sprint 5
coupling and table ownership — the boundaries above are candidates, confirmed only
once real coupling is measured.

| Phase | Days | Scope |
|---|---|---|
| 6A | 16–22 | Coupling audit; one owner per table; contracts; Identity; Academic; Gateway; Discovery; Config; Learning; Assessment + Attendance |
| 6B | 23–27 | Kafka contracts; versioning; idempotency; retry/DLQ; correlation/causation; read models; Feign where justified; Resilience4j; AI and Realtime services |
| 6C | 28–30 | OpenTelemetry; Prometheus; Grafana; Loki/Tempo; failure tests; security tests; concurrency/load tests; full Docker Compose; final demo |

## 13. Testing / quality gates

| Category | Minimum evidence |
|---|---|
| Unit | Enrollment, prerequisites, grade calculation, risk engine, permission evaluator |
| Integration | Migrations, transaction boundaries, outbox, Kafka consumer idempotency |
| Security | Auth failures, passkey validation, Argon2id, IDOR, privilege escalation, tenant escape, rate limits |
| Concurrency | Last-seat race, duplicate QR scan, duplicate payment webhook, concurrent grade update |
| Realtime | Unauthorized subscription rejected; revoked session blocked; authorized delivery |
| AI | Tool authorization, prompt-injection resistance, schema validation, tenant-safe retrieval, source attribution |
| Accessibility | Keyboard/focus, forms/errors, dialogs/tables/charts, reduced motion, authentication/QR accessibility |
| Performance | Pagination, N+1 detection, cache behavior, dashboard aggregation, Kafka lag, WebSocket fan-out |
| Contracts | OpenAPI, event schemas, service compatibility |

## 14. Demo validation targets

- Authenticated CRUD: < 500 ms typical
- Cached dashboard aggregate: < 200–300 ms typical
- Enrollment command: < 500–800 ms typical
- QR acknowledgement: near-real-time, < 1 s target on local demo
- AI: asynchronous queued/running/completed state

These are validation targets, not production SLA claims. Avoid N+1 queries,
paginate large sets, keep expensive analytics off synchronous enrollment/attendance
paths.

## 15. Do not build yet

- Kubernetes
- One microservice per table
- Full event sourcing
- Unlimited AI agents
- Raw LLM DB access
- Heavy search cluster on Day 1
- Video transcoding pipeline
- WebRTC classroom infrastructure
- Permanent binary storage in PostgreSQL
- Guardian master-switch access
- Hard-coded semester-only model
- AI deciding authoritative attendance/grades/enrollment eligibility/discipline
- Blanket exact-once delivery guarantee claims

## 16. Final review business flow

```
Passkey login -> academic dashboard -> live QR attendance -> authenticated scan
-> backend validation -> WebSocket update -> assessment -> grade event
-> risk recalculation -> advisor alert -> AI evidence-grounded explanation
-> Grafana -> Sprint 6 distributed architecture walkthrough
```

## 17. Canonicalization instruction

1. Ignore conflicting older README/PRD/agent files.
2. Do not revert to CMS scope.
3. Do not remove LMS, finance, career, transcript, waitlist, overrides, guardian,
   success engine, or RAG.
4. Do not generate entities until schema/invariants/state machines are reviewed.
5. First produce: final ERD, migration spec, authorization matrix, state machines,
   OpenAPI contract list, Kafka schemas, module ownership, Sprint 5 plan, Sprint 6
   extraction map.
6. For every entity, document purpose, columns, PK/FK, cardinality, unique
   constraints, indexes, tenant ownership, lifecycle, audit, concurrency,
   transaction boundary and events.
7. Identify contradictions before implementation.
8. Never invent facts to make a model fit.
9. Keep Sprint 5 modular boundaries strict.
10. Add infrastructure only when it has a clear engineering/business reason.
11. If scope conflicts reappear, stop and flag them explicitly.

## 18. Definition of done before coding

- [ ] Canonical scope agreed
- [ ] ERD has no unresolved contradictions
- [ ] Table ownership explicit
- [ ] Authorization beyond roles defined
- [ ] Authentication flow defined
- [ ] State machines documented
- [ ] Transaction/concurrency strategy defined
- [ ] Kafka contracts versioned
- [ ] Realtime authorization defined
- [ ] AI tool/RAG boundaries explicit
- [ ] Testing gates exist
- [ ] Sprint 5 and Sprint 6 linked through extraction seams

Only after these gates pass should entity and service implementation begin.
