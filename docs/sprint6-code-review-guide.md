# Sprint 6 code review: a guide for the person presenting

Order of the review: **(1) the structure, where everything is; (2) the main feature, the AI advisor, end to end; (3) the pieces it stands on, Kafka and Redis;
(4) the microservices, which is what Sprint 6 is about.** Part 4 is the thread through all of it, so it comes first below as "the one-minute version".

Every claim has a file or a contract behind it. Where something is not finished, it says so: a reviewer trusts a presenter who names the gaps first.

---

## 0. The one-minute version

Sprint 5 built **one Spring Modulith application** with module boundaries that tests enforce. Sprint 6 **pulled services out of it along those boundaries**, one at
a time, without changing what any module decides.

- **Nine containers**: a **gateway**, eight application processes (**identity, academic, learning, assessment, attendance, notification, ai**) and the **core**,
  which still holds what has not been extracted yet (record, risk, career, finance, onboarding).
- **They talk in three ways, and only three**: HTTP through the gateway (with the caller's own token), **Kafka events** written through a transactional outbox, and
  **Redis** for short-lived shared state (service discovery, revoked logins, rate limits, AI job status).
- **Each service keeps its own copy of the facts it decides with**, kept current by events, so a request never leaves the process to be authorised.
- **The AI service has no database at all.** It sees student data only through nine tools, each answered by the data service **with the user's own token**.

What is honestly not done (say this before they ask): **Notification already runs on a PostgreSQL server of its own** (the proof of the data split); the other services still share **one PostgreSQL instance** (one schema per module; ownership is enforced by tests **and by a separate database login per service**,
[contracts/data-ownership.md](contracts/data-ownership.md) section 1a; the split-per-service script exists); the **core is still a monolith** for five modules; and it is **one
Maven module producing one jar**, started with a different entry class per service. It is a modular monolith that has been *deployed* as services, with the
data split prepared and verified but not done.

---

## 1. The structure: where everything is

### 1.1 The repository

| Path | What is there |
|---|---|
| `backend/` | The Spring Boot 4 / Java 25 application: every module and every service entry point. One Maven module, one jar ([`backend/Dockerfile`](../backend/Dockerfile) builds the one image all nine containers use) |
| `frontend/` | React + TypeScript + Vite. `src/pages/<role>/` (admin, faculty, student, guardian, placement, superadmin), `src/components/`, `src/api/` (the one client), `src/lib/`, `src/test/` (176 tests, including WCAG 2.2 AA checks of every page) |
| `infra/db/migrations/` | **103 Flyway migrations** (V1 to V103), the single source of the schema. Owned by the core, shipped inside the image |
| `infra/db/split/` | `split-service.sh`: the snapshot export that gives a service its own database when the time comes |
| `infra/docker/` | `docker-compose.yml` (Postgres with pgvector, Redis, Kafka, Prometheus, Grafana, Loki, Tempo) and `docker-compose.services.yml` (the nine services) |
| `infra/scripts/` | smoke tests (`smoke-flow.js`, `smoke-websocket.js`), the advisor's 22-question evaluation (`advisor-eval.js`), key helpers, `reset-demo.ps1` |
| `docs/` | The canonical spec, PRD/TRD, ERD, state machines, **`contracts/`** (one contract per service and mechanism), the event catalogue and frozen schemas, the validation report, the roadmap |

### 1.2 The backend: one package per module (`backend/src/main/java/com/setu/`)

| Package | Owns | Runs in |
|---|---|---|
| `gateway` | the front door: routing, token check, rate limit, correlation ids, the error envelope, the WebSocket relay | **Gateway** :8080 |
| `identity` | accounts, passkeys (WebAuthn), Argon2id, JWT issuing + published keys (JWKS), refresh rotation, revocation | **Identity** :8081 |
| `institution`, `catalog`, `enrollment`, `directory`, `guardianaccess` | colleges, policy documents (and their search index), programs/courses/sections, the enrollment state machine, people, guardian grants | **Academic** :8082 |
| `learning` | modules, lessons, resources, assignments, progress | **Learning** :8084 |
| `assessment` | assessments, attempts, versioned append-only grades | **Assessment** :8085 |
| `attendance` | live QR attendance (WebSocket), staff attendance and check-in locations | **Attendance** :8086 |
| `notification` | turns events into in-app notifications, delivers them live | **Notification** :8087 |
| `aiexplain` | the AI advisor: chat, tools, memory, jobs, provider fallback | **AI** :8088 |
| `aifacts` | what the advisor's tools return, and who may see it | **Core** (the data side of the AI boundary) |
| `record`, `risk`, `career`, `finance`, `analytics` | transcripts and GPA, the Student Success risk engine, placement, fees and Razorpay, dashboards | **Core** :8083, not extracted yet |
| `shared` | the kernel: authorization engine, tenant context, outbox, event envelope, read-model projectors, discovery, resilience, WebSocket support, the embedder contract | every service |

Each service is an entry class that sets `setu.service=<name>` and component-scans only its own packages plus `shared`
(for example [`AcademicServiceApplication`](../backend/src/main/java/com/setu/academic/AcademicServiceApplication.java)). The core is `BackendApplication`.
**Inside a module the layers are `domain` (rules, no framework), `application` (services, the transactions), `infrastructure` (web controllers, repositories, Kafka).**

### 1.3 How the boundaries are kept (this is what makes extraction safe)

- **Spring Modulith's `ModularityTests`** fail the build if a module reaches into another module's internals.
- **Frozen contract files** in `backend/src/test/resources/`: `module-contracts.txt` (every allowed module-to-module edge, 77 lines), `event-schemas.txt`
  (**52 events**, every field), `table-owners.txt` (every table has exactly one owning module), `cross-service-foreign-keys.txt` (the foreign keys that still cross a
  future service boundary, named so they can be removed deliberately). A change to any of them fails a test until the file is regenerated and reviewed.
- **Ports instead of imports.** When Career needs a student's CGPA it asks `shared.career.StudentStandings`; AI facts answers it. Career never imports Record,
  Attendance or Enrollment. Same for `shared.academic.AcademicDirectory`, `RecordDirectory`, `StudentCohorts`.
- **The AI module never gets a datasource** (an architecture test, and in the AI service it is a fact of the build: the persistence auto-configuration is excluded).

### 1.4 Where to look first, in order (a good walk for the review)

1. [`docs/architecture-walkthrough.md`](architecture-walkthrough.md): the shape and the §16 flow across the services, with links to the contract and test behind each step.
2. `docker-compose.services.yml`: the nine services, ports, health checks.
3. [`GatewayFilter`](../backend/src/main/java/com/setu/gateway/GatewayFilter.java) and [`contracts/gateway.md`](contracts/gateway.md): the route table and what happens to a request.
4. [`shared/governance/OutboxEventWriter`](../backend/src/main/java/com/setu/shared/governance/OutboxEventWriter.java) and `OutboxPublisher`: how an event is born.
5. [`shared/readmodel/`](../backend/src/main/java/com/setu/shared/readmodel/): how a service keeps its own copy.
6. [`aiexplain/`](../backend/src/main/java/com/setu/aiexplain/) and [`institution/…/PolicyDocumentService`](../backend/src/main/java/com/setu/institution/application/PolicyDocumentService.java): the AI feature.

---

## 2. The microservices

### 2.1 What each is and how they are wired

```
browser ──▶ Gateway :8080 ──┬──▶ Identity :8081      /auth /identity /.well-known
  (JWT)     CORS, JWT check,├──▶ Academic :8082      /institution /catalog /enrollment /directory /guardian-access
            rate limit,     ├──▶ Learning :8084      /learning
            correlation id, ├──▶ Assessment :8085    /assessment
            one error       ├──▶ Attendance :8086    /attendance /staff-attendance   (+ /ws relayed frame for frame)
            envelope        ├──▶ Notification :8087  /notifications
                            ├──▶ AI :8088            /ai
                            └──▶ Core :8083          everything not yet extracted, and /ai-facts
        Kafka  ◀── outbox ── every service writes events        Redis ◀── discovery, revocations, rate limits, AI jobs
```

- **The gateway is hand-written, not Spring Cloud Gateway** ([contracts/gateway.md](contracts/gateway.md) §1 says why). Routes are configuration; the most
  specific pattern wins. `GatewayRouteCoverageTests` reads every endpoint from the running application and fails if any would reach the wrong service.
- **Discovery is health-aware**: each service writes `setu:services:<name>` to Redis every few seconds (`ServiceRegistrar`); the gateway reads them
  (`RedisServiceDirectory`) and quarantines an instance that refused a connection.
- **Authentication is by published key**: Identity signs ES256 tokens and publishes its public key (JWKS). Every other service verifies a token with that public key
  alone, and checks a Redis revocation list, so the signing key lives in one place only.
- **Authorization is local**: the shared `AuthorizationEngine` decides from the service's own copy of roles and permissions (below), not by calling Identity.

### 2.2 How a service gets its facts without calling anyone: the read model

Learning, Assessment, Attendance, Notification and Academic each hold **`rm_*` tables** in their own schema: roles, students, faculty, assignments, enrollments, sections,
colleges, guardian grants. They are written **only by projectors** that consume Identity and Academic events
([contracts/extracted-services.md](contracts/extracted-services.md) §2). Properties worth saying out loud:

- **Convergent**: each row remembers the time of the event that last wrote it, and an older event never overwrites a newer one, so a replay from the start, a
  redelivery, or the whole history in reverse all end in the same state (`AcademicReadModelParityTests` proves all three).
- **Idempotent**: every projector records processed event ids in `rm_processed_events`.
- **Parity-tested**: the permission matrix copied into each service is compared to Identity's by test, so a permission added in a migration cannot drift.
- **Honest consequence**: a role change reaches another service **in seconds, not in the same transaction**. Logouts and suspensions do not wait: they travel on the
  Redis revocation list. A revoked guardian grant is confirmed with Academic by a guarded synchronous call before a guardian's "yes" is honoured.

### 2.3 Resilience when a service is down

Calls between services go through `GuardedCall`: **500 ms connect, 8 s response, no retries, a circuit breaker (opens at 50% failures over 10+ calls), a bulkhead of
20 concurrent callers**. The rule everywhere: **"unavailable" is never turned into "empty"**. The gateway answers `502 BAD_GATEWAY` in the standard envelope; the AI
tools answer "this information is not available right now". There is no fake fallback data anywhere ([contracts/data-ownership.md](contracts/data-ownership.md) §3).

---

## 3. Kafka

### 3.1 The pattern: a transactional outbox

1. A business change and its event are written **in the same database transaction**: `OutboxEventWriter` inserts a row into `governance.outbox_events`.
   So an event is never announced for a change that rolled back, and a change is never made without its event.
2. `OutboxPublisher` (a poller in every process that writes events) reads `NEW` rows, sends each to Kafka, and marks it `PUBLISHED` **only after the broker acknowledged it**.
3. Topic = `setu.<aggregate_type>` (for example `setu.grades`, `setu.payments`, `setu.applications`). The ordering key is the aggregate's, so events about one
   student stay in order. Headers carry `event_id` and `tenant_id`; the payload carries the rest ([contracts/kafka-contracts.md](contracts/kafka-contracts.md) §1).

### 3.2 What is guaranteed, said honestly

- **At-least-once, never "exactly-once"**. If the process dies between the broker's acknowledgement and the commit, the row is published again: a real duplicate.
  So **every consumer is idempotent**, inserting the event id into a processed-events table in the same transaction as its effect (the insert is the guard, not a
  prior read, so two racing consumers cannot both act).
- **Versioned and frozen**: 52 event schemas in `event-schemas.txt` and [event-schemas.md](event-schemas.md). A consumer that meets a version it does not know
  refuses it (`UnsupportedEventVersionException`) instead of guessing.
- **Retry and dead letters**: a failing event is delivered **three times with backoff, then published to `<topic>.DLQ`**. Malformed or contradicting events (for example a
  tenant header that does not match the payload) are *poison*: dead-lettered at once, never retried forever. Proven with a real poison event on the real stack.
- **Correlation and causation ids** travel with every event so one flow can be followed across services in the logs and traces.

### 3.3 Who consumes what

| Consumer (group) | Runs in | Reads | Does |
|---|---|---|---|
| Read-model projectors (`setu-<service>`) | Academic, Learning, Assessment, Attendance, Notification | Identity and Academic topics | keep each service's own copy current |
| Notification (`setu-notification`) | Notification service (the core contains the module too and joins the same group) | grades, payments, placement, applications, recommendations, interventions, student profiles, enrollments, waitlist | one in-app notification per event id, pushed live to `/topic/notifications/<userId>` |
| Risk (`setu-risk`) | Core (the risk module is not extracted) | grades, attendance sessions | recalculate a student's risk score when a grade is published or a session closes |
| Final grade (`setu-enrollment`) | Academic (the core contains the module too and joins the same group) | grades | publishing a final grade completes the enrollment |

Because the core still contains every module, it also starts these consumers; a **shared consumer group** means each event is handled by whichever instance owns its
partition, and idempotent consumers make a redelivery harmless. Saying this unprompted is better than being asked why a "monolith" is reading Kafka.

Example to walk through: an officer shortlists a student → `career` writes `ApplicationDecided` to the outbox → published to `setu.applications` → the Notification
service creates "You have been shortlisted" (with the officer's reason) → the student's bell updates. Tested end to end, and run live.

---

## 4. Redis

Redis is **never a source of truth**: every key expires or can be rebuilt. What is in it:

| Key | Used by | Purpose |
|---|---|---|
| `setu:services:<name>` | every service writes, gateway reads | discovery and health-aware routing (renewed every few seconds, gone on shutdown or crash) |
| `setu:revoked:login:<familyId>`, `setu:revoked:account:<userId>` | Identity writes, every service reads (fails closed) | logout and suspension take effect immediately, ahead of the 15-minute token expiry; keys expire with the token |
| rate-limit counters | gateway (per user, or per address for the anonymous, per route), Identity (sign-in attempts), AI (per user and college) | fixed-window `INCR` + `EXPIRE`, an abuse guard, not a billing meter |
| `setu:ai:job:<id>` | AI service | status and result of a background advisor question, kept an hour, readable only by its owner |

Say plainly: the fixed-window limiter can admit a little more than the limit at a window boundary.

---

## 5. The main feature: the AI advisor

### 5.1 What it is, and the rules it was built under

A student (or an advisor or guardian, within what they may see) asks a question in plain language: "what is my attendance in each course?", "what does the attendance
policy say?", "do I meet the skills for this job?". The advisor answers **only from the college's own records**, shows **what each answer is based on**, and never changes
anything. The rules, from the spec: the AI never decides attendance, grades, eligibility or discipline; it never has raw database access; every explanation's input is
logged; retrieved text is data, never instructions.

### 5.2 One question, end to end

1. The browser calls `POST /ai/chat` (or the streaming variant) **through the gateway**, with the user's token.
2. **AI service** ([`AiChatService`](../backend/src/main/java/com/setu/aiexplain/application/AiChatService.java)): checks the **rate limit** in Redis (per user and college), loads the
   user's **conversation memory** (the last six exchanges, plain text only, 30 minutes idle, kept in Redis so it survives a restart and is shared by every instance (an in-process fallback if Redis is down), never tool arguments or results), and calls the model.
3. The model is offered **nine tools**: attendance, academic progress, assessment results, upcoming deadlines, skill gap, **policy search**, learning recommendations,
   transcript, fee status. **No tool takes a tenant, a user, a token or a role**: only "which student" or nothing (meaning the caller).
4. When the model calls a tool, `AiToolService` asks the **secure tool boundary**, the `AiFacts` interface. In the AI service the implementation is `RemoteAiFacts`:
   an HTTP call through the gateway to `/ai-facts/**` **carrying the user's own token**.
5. In the **data service** (core), `AiFactsService` authenticates that token like any request and decides with that user's identity, college and relationships
   (`AiAccessSupport`: own / advisor of that student / administrator / guardian with an active grant). It reads the records and returns JSON. A refusal comes back as a
   normal result for the model to report ("not authorised").
6. The model writes the answer. **The audit record `AI_CHAT_COMPLETED` is written as part of answering: if it cannot be written, the answer is not given** (503).
7. The reply names the model that answered and lists the **evidence**: which tools were used, with a link to the same records on the student's own pages.

**Why this holds up in a review:** the AI service holds **no credential of its own** that could read anything and has **no database**, so a compromised or misled
advisor can see no more than the signed-in user. The model never decides who may see what. Proven by `AiToolAuthorizationTests` (20 tests), `AiFactsControllerTests`,
`RemoteAiFactsTests`, and prompt-injection tests.

### 5.3 The model, and what happens when it is slow or down

Order tried: **primary Gemini model → a second Gemini model → a hosted NVIDIA model (only if a key is set) → local Ollama as the last resort**. After a primary failure it
is skipped for 60 seconds so one slow provider does not delay every question. An answer that has started streaming is never continued by another model. Retries are capped
so a provider outage is seen by the fallback logic instead of being hidden for minutes. The advisor was evaluated on the running stack with 22 questions
(`infra/scripts/advisor-eval.js`): attendance overall and per course, follow-ups, deadlines, policy questions, a course and a policy that do not exist, another student's
attendance (refused, including under "ignore your instructions"), off-topic questions.

### 5.4 RAG over college policy documents: chunks, embeddings, search

This is the part most reviewers ask about. Everything below lives in the **data service** (the `institution` module), never in the AI service: **the AI service never sees
another college's documents and has no embedder.** Today the data service is the **core process**, which still contains every module including `institution`, so the
policy search runs there even though the `institution` module also runs in the Academic service for the rest of its work.

**Indexing (when a policy document is created, and by a background job)**

1. **Chunking**, plain code, no model ([`PolicyChunker`](../backend/src/main/java/com/setu/institution/domain/PolicyChunker.java)): split on paragraph breaks, then on sentence
   ends, packing sentences up to **600 characters**. When a long paragraph is cut, **the last sentence is repeated at the start of the next chunk** so an idea that straddles a boundary
   is still found whole. Never splits inside a word, never returns an empty chunk.
2. **Embedding**: each chunk is turned into a **768-number vector** by the `TextEmbedder`. With Gemini as the chat provider this is **`gemini-embedding-001`**; with no key the
   built-in **`HashingTextEmbedder`** (feature hashing of words and word pairs: it finds shared words, **not meaning**, and says so in its own javadoc); Ollama's `nomic-embed-text` if asked for.
3. **Storage**: `institution.policy_chunks` (V79) holds the text, a **pgvector `VECTOR(768)`** with an **HNSW cosine index**, a generated **full-text `tsvector`** with a GIN index,
   and **`embedding_model`**: the id of the embedder that made the vector. **Vectors from different models are not comparable**, so a query only uses vectors of its own model.
4. **`PolicyIndexer`** keeps the index current: it chunks documents that have none and (re-)embeds any chunk with **no vector or a vector from a different model**, every 60 seconds.
   **An embedder being down never blocks creating a document**: the chunk is stored without a vector, is found by full-text search immediately, and gets its vector later.

**Searching (the `searchCollegePolicy` tool)**

1. The tenant comes from the request's security context, **never from the prompt**. The query is the user's own question, never re-derived from retrieved text.
2. **Access control happens inside the SQL, before anything is ranked** ([`PolicyChunkStore`](../backend/src/main/java/com/setu/institution/infrastructure/PolicyChunkStore.java)):
   `WHERE tenant_id = :tenant AND (required_permission_code IS NULL OR required_permission_code IN (:codes held by this caller))`. A passage the caller may not see is never
   ranked, returned or handed to the model: filtering *before* retrieval, not after, is a non-negotiable (spec §10).
3. Two searches run: **vector** (the query is embedded; nearest chunks by cosine similarity; anything below the embedder's floor, **0.62 for Gemini**, is treated as unrelated) and
   **full-text** (stemmed, `ts_rank`). They are merged by **reciprocal-rank fusion** (k = 60, 10 candidates from each, **top 5** returned): a passage ranked well by both rises most.
4. If the embedder is unavailable, **full-text alone answers**. If nothing clears the floor, **nothing is returned** ("no policies found") rather than the least-bad passage.
5. The retrieved passages go to the model as **data**. A document that says "ignore your instructions" is treated as text (prompt-injection tests treat retrieved content as untrusted).

**Say plainly:** it is RAG over **policy documents only**, not over student data (student data comes through the nine tools, which return structured facts, not retrieved text);
the demo college has a handful of policy documents, so the retrieval quality is shown by tests and the evaluation, not by scale; there is no re-ranking model and no
chunk-level citation UI beyond naming the document.

### 5.5 Background jobs and live status

`POST /ai/jobs` → `202 {jobId, QUEUED}`. The same single bounded model call runs **as the person who asked** (their identity and token are captured at submission) on 4 workers
with a queue of 50, never accepted-then-dropped. Status `QUEUED → RUNNING → COMPLETED | FAILED` is pushed over WebSocket to `/topic/ai/jobs/<userId>` (status only, never the
answer); the result is read with `GET /ai/jobs/{id}`; the record lives in Redis for an hour and is visible to its owner only.

---

## 6. Questions a reviewer is likely to ask, with straight answers

| Question | Answer |
|---|---|
| Are these real microservices if they share a database? | They are separately deployed processes with their own schemas, read models and event-driven integration, and each table has one owner enforced by a test **and by the database**: every service logs in as its own database role that can use only its own schemas (`infra/db/roles/service-roles.sql`; `ServiceDatabaseRolesTests` tries to break it). **One service already has its own database server** (Notification: [contracts/data-ownership.md](contracts/data-ownership.md) section 4a: an event from Assessment through Kafka lands in *its* database, and stopping that database stops only Notification). The **other services still share one Postgres instance**, and the outbox and audit tables are shared by design until each gets its own. A split service also **migrates its own database** (`infra/db/services/<service>` plus `infra/db/platform`, the core applying the same files to the shared copy, one version number across all of them, held by `ServiceMigrationsTests`); what is left is repeating it for the other services (Notification, Attendance, Assessment and Learning have their own databases; the last three each needed their in-process readers moved behind a port first: `AttendanceCounts`, `AssessmentFacts`, `LearningFacts`; Identity and Academic remain). The per-service split is scripted and the foreign keys that still cross a boundary are frozen in a named list so they can be removed deliberately. Honest status: *extraction proven, data split prepared*. |
| Why one jar for all services? | One image keeps builds and the contract tests simple while boundaries settled. Each service already scans only its own packages. Separate artifacts are a build change, not a design change. |
| Why is the core still a monolith? | Record, risk, career and finance have not been moved. Record depends on Academic data that needs more events before it can have its own read model. It is listed as the next step in [known-limitations-and-roadmap.md](known-limitations-and-roadmap.md). |
| Is Kafka exactly-once? | No, and the docs never claim it. At-least-once plus idempotent consumers, which is what is actually achievable. |
| What if Kafka is down? | Events stay `NEW` in the outbox and are published when it returns; nothing is lost and no business transaction fails because of it. Read models go stale in the meantime; revocations still work (Redis). |
| What if Redis is down? | Revocation reads **fail closed** (a request is refused rather than trusted). The rate limiters **fail open** (briefly unmetered, logged: an abuse guard going quiet is a smaller risk than an outage taking every request down). Discovery **keeps the last answer and the configured list**. Nothing is stored only in Redis that matters, except AI job records, which are lost and read as "not found" (the client resubmits). |
| Why is the AI service separate? | So it can hold **no data and no credential**, which makes the boundary a fact of the build. It scales and fails independently of the data, and its failure never blocks a grade or an attendance scan. |
| Could the model leak another student's data? | Not through a tool: no tool takes a user or a tenant, the check runs in the data service with the **user's** token every time, and the tests include "ignore your instructions" and instructions hidden in a policy. The model's *words* are the only unchecked part, which is why every answer lists its evidence. |
| Where are embeddings stored, and what if I change the model? | In `policy_chunks.embedding` (pgvector, 768 dimensions, HNSW cosine), tagged with the model id. Changing the model makes the old vectors invisible to the vector half of the search (full-text still finds them) until `PolicyIndexer` re-embeds them, about 25 chunks a batch, every minute. |
| How do I know the system works end to end? | `infra/scripts/smoke-flow.js` (19 steps across the services), `smoke-websocket.js`, the 22-question advisor evaluation, and [validation-report.md](validation-report.md), which lists what was run live and what was not. |

---

## 7. Where each claim is proven

| Claim | Contract | Test |
|---|---|---|
| Module boundaries hold | `module-contracts.txt` | `ModularityTests`, `ModuleContractFreezeTests` |
| Every table has one owner | `table-owners.txt`, [contracts/data-ownership.md](contracts/data-ownership.md) | `TableOwnershipTests` |
| Events are frozen and versioned | [event-schemas.md](event-schemas.md), [contracts/kafka-contracts.md](contracts/kafka-contracts.md) | `EventSchemaFreezeTests` |
| Read models converge and match | [contracts/extracted-services.md](contracts/extracted-services.md) | `AcademicReadModelParityTests`, `ReadModelParityTests` |
| The gateway routes every endpoint correctly | [contracts/gateway.md](contracts/gateway.md) | `GatewayRouteCoverageTests` |
| The AI boundary holds across the network | [contracts/ai-service.md](contracts/ai-service.md) | `AiToolAuthorizationTests`, `RemoteAiFactsTests`, `AiPolicySearchTests` |
| Notifications are exactly-once per event | [kafka-event-catalog.md](kafka-event-catalog.md) | `NotificationEventProcessorTests` |
| Everything ran as real containers | [contracts/containers.md](contracts/containers.md) | `smoke-flow.js`, `smoke-websocket.js` |
