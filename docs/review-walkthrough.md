# Sprint 6 code review: walkthrough and files to show

Say it in this order. Times are rough.

## 1. Code structure (about 2 min)

- `backend/src/main/java/com/setu/`: one Spring Boot codebase (a modular monolith, Spring Modulith). One package per module: identity, directory, institution, catalog, enrollment, attendance, learning, assessment, record, finance, career, risk, aiexplain, notification, analytics. `shared/` holds common pieces, `gateway/` is the edge. An architecture test fails the build if one module reads another module's repository.
- `infra/db/migrations`: Flyway SQL, one schema per module. New: `infra/db/services/<service>` (a service's own migrations) and `infra/db/platform` (tables every database carries). `infra/db/roles` holds the per-service database logins.
- `infra/docker`: compose files for the 9 containers (gateway, core, identity, academic, learning, assessment, attendance, notification, AI/realtime), with Postgres, Redis, Kafka and a second Postgres for Notification.
- `frontend/`: the "ledger" UI.
- `docs/`: canonical spec, `contracts/`, `validation-report.md`, `sprint6-code-review-guide.md`.

Files: `docs/contracts/containers.md`, `infra/docker/docker-compose.services.yml`, `docs/contracts/` (one document per service plus `kafka-contracts.md`).

## 2. The microservices part (about 4 min)

- One image, many roles: the same backend image runs as a service when a profile switches on only that service's modules.
- Gateway: verifies the JWT, rate-limits, adds correlation IDs, routes by path. Services are found through Redis keys `setu:services:*` (health-aware).
- Identity issues JWTs and publishes its keys; every other service only verifies tokens.
- Data ownership: every table has one owner (enforced by a test). Each service logs in as its own database role, able to touch only its own schemas. Notification has its own PostgreSQL server and migrates its own database.
- No cross-service joins: services keep read models (`rm_*` tables) fed by events; the projectors converge, so replays are safe.

Files to show:

| File | Point |
|---|---|
| `docs/contracts/extracted-services.md` | which modules became services, which stayed in the core |
| `backend/src/main/java/com/setu/shared/discovery/ServiceRegistry.java` | service discovery through Redis |
| `docs/contracts/gateway.md` | JWT check, rate limit, routing, correlation IDs |
| `docs/contracts/data-ownership.md` | one owner per table; sections 1a (roles), 4a (Notification's own server), 4b (own migrations) |
| `backend/src/test/java/com/setu/audit/TableOwnershipTests.java` | the test that enforces ownership |
| `infra/db/roles/service-roles.sql` | each service logs in as its own role |

## 3. Per-service migrations (built today)

| File | Point |
|---|---|
| `infra/db/services/notification/V104__notification_unread_index.sql` | first service-owned migration |
| `infra/db/platform/README.md` | rules for the shared platform tables |
| `infra/db/split/provision-service-db.sh` | copies the schema and writes a baseline row into the new database's Flyway history |
| `backend/src/test/java/com/setu/audit/ServiceMigrationsTests.java` | the rules, enforced without a database |

Live proof: in `setu-postgres-notification-1`, `public.flyway_schema_history` shows BASELINE 103 and then V104; the shared database shows V104 too; the service's login cannot read the history table.

## 4. Main lifecycle: grade, event, risk, notification (about 3 min)

1. Faculty publishes a final grade (Assessment service).
2. In the same transaction it writes the grade and an outbox row (`governance.outbox_events`): the transactional outbox, no dual write.
3. `OutboxPublisher` sends it to Kafka topic `setu.grades` (at-least-once).
4. Consumers record the event ID in a processed-events table, so a redelivery does nothing. After 3 failed deliveries the event goes to the dead-letter topic.
5. Academic completes the enrollment; Risk recalculates; Notification writes to its own database.
6. The student sees it over WebSocket or on the next request.

Say: "No service calls another to make this happen; they react to events, so one being down delays its part but does not break the others."

| File | Point |
|---|---|
| `backend/src/main/java/com/setu/shared/governance/OutboxPublisher.java` | outbox publisher |
| `docs/contracts/kafka-contracts.md` | versioned schemas, idempotent consumers, dead-letter topic |
| `backend/src/main/java/com/setu/risk/application/RiskEventProcessor.java` | consumes `GradePublished` and `AttendanceSessionClosed`; the processed-event insert is the duplicate guard |

## 5. The AI advisor (about 3 min)

- `ai-explain` has no datasource. It calls 9 application-owned tools through `AiFacts`. In split mode `RemoteAiFacts` calls services with the user's own token, so the AI never sees more than that user.
- Gemini is primary with a deterministic fallback, so the answer degrades honestly.
- Structured output is validated before anything is shown or stored.

| File | Point |
|---|---|
| `backend/src/main/java/com/setu/shared/aifacts/AiFacts.java` | the tool interface |
| `backend/src/main/java/com/setu/aiexplain/infrastructure/facts/RemoteAiFacts.java` | calls with the user's token |
| `docs/contracts/ai-service.md` | tools, fallback chain, validation |

## 6. RAG: chunks, embeddings, retrieval (about 3 min)

- Chunking: `PolicyChunker` splits college documents into chunks of about 600 characters.
- Embedding: `TextEmbedder` makes 768-dimension vectors with Gemini, with a hashing fallback when the key or network is missing.
- Storage: pgvector with an HNSW index, plus a tsvector column for keywords.
- Retrieval: vector and keyword results are merged with reciprocal-rank fusion. Tenant and document-ACL filtering happens in the SQL, before retrieval. Answers carry source references; retrieved text is treated as untrusted (there is a prompt-injection test).

Files: `backend/src/main/java/com/setu/institution/domain/PolicyChunker.java`, `backend/src/main/java/com/setu/shared/ai/TextEmbedder.java`. For the retrieval query, use the RAG section of `docs/sprint6-code-review-guide.md`.

## 7. Kafka and Redis in one line each

- Kafka carries domain events with versioned schemas, correlation and causation IDs, idempotent consumers and a dead-letter topic.
- Redis holds service discovery, token revocations, rate limits (which fail open) and AI job records. It is never the source of truth.

## 8. Close with today's work and the honest limits

Done: per-service database roles, Notification on its own server, per-service migrations (one version number across all folders, baseline row for the copy).

Limits to state before they ask:

- Only Notification and Attendance have their own databases and migrations (Attendance went last; its readers in the core now ask the service).
- Finance, career, record and risk are still inside the core.
- Flyway runs with the database owner's login.
- The smoke flow's risk step can show "no new score" when the student's inputs are unchanged (an identical recalculation is not written as a new row).

## Live demo order

1. `docker ps`: containers healthy.
2. `node infra/scripts/smoke-flow.js`.
3. Query `public.flyway_schema_history` in `setu-postgres-notification-1`: baseline 103 and V104.

Your speaking notes, if you open one file: `docs/sprint6-code-review-guide.md`. Proof for each claim: `docs/validation-report.md`.
