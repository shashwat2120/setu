# Architecture walkthrough: the same flow, distributed (Sprint 6C.3)

Sprint 5 built one Spring Modulith application with enforced module boundaries. Sprint 6 pulled services out of it along those boundaries,
one at a time, without changing what the modules decide. This page follows the §16 flow ([final-demo.md](final-demo.md)) across the result.
Every statement links to the contract that holds it and the test that checks it.

> Everything on this page was run for real, as nine containers, on Linux: [contracts/containers.md](contracts/containers.md) says how, and what it showed.

## 1. The shape

```
                         browser (React)
                              |
                       +------v------+      CORS, JWT check, rate limit,
                       |   Gateway   |      correlation id, one error envelope,
                       |    :8080    |      /actuator/** answered by the gateway itself
                       +------+------+
   /auth /.well-known         |        /institution /catalog /enrollment /directory /guardian-access
  +---------------------------+---------------------------+----------------------------+
  v                           v                           v                            v
Identity :8081          Academic :8082            Learning :8084 / Assessment :8085     Attendance :8086
 accounts, passkeys,     colleges, catalog,        /learning   /assessment              /attendance /staff-attendance
 JWT issuer + JWKS,      enrollment, directory,                                           STOMP served directly
 refresh rotation        guardian grants
  |                           |                           |                            |
  +----------- events (outbox -> Kafka, setu.<aggregate>) ----------------------------+
                              |
        +---------------------+-----------------------+
        v                                             v
 Notification :8087 (/notifications)          read models in every service
 consumer, bell, STOMP per user                (rm_* tables fed by events)

 AI :8088 (/ai)  -- no datasource; reads facts through the secure tool boundary --> the service that holds the data

 core (the original process, route /**): record, risk, career, finance, onboarding   [not yet extracted]
 Config :8888 (config server, secrets outside the repo)      Discovery: services advertise themselves, health-aware routing
```

Nine service entry points exist (`setu.service=` identity, academic, learning, assessment, attendance, notification, ai, gateway, config), each
inert inside the monolith unless selected, which is why the whole suite still runs as one application and the same code is both. Contracts:
[identity-service](contracts/identity-service.md), [academic-service](contracts/academic-service.md), [extracted-services](contracts/extracted-services.md),
[gateway](contracts/gateway.md), [config-server](contracts/config-server.md), [notification-service](contracts/notification-service.md), [ai-service](contracts/ai-service.md).

## 2. Following the flow

**Login.** The browser talks only to the gateway. `/auth/**` is open at the edge and routed to Identity, which runs the WebAuthn ceremony (or the
Argon2id password fallback, rate limited) and issues an ES256 access token; its public keys are published at `/.well-known/jwks.json`.
Every other service verifies tokens against those published keys and a revocation list, so no request goes back to Identity to be authorized.
A role change reaches the other services as an event (`UserRoleAssigned`/`UserRoleRevoked`) into their `rm_role_*` copy; a logout or suspension
travels on the revocation list and does not wait for an event.

**Dashboard and the open session.** Academic answers `/enrollment/**` and the rest of its routes. Faculty opening an attendance session hits
Attendance, which asks "is this person faculty here, assigned to this section, with a meeting that belongs to it" of its **own copy** of those
facts (`AcademicDirectory` answered by `ReadModelAcademicDirectory`), not of Academic over the network.

**Scan and validation.** The student's scan is validated by Attendance for token, tenant, expiry, enrollment and duplicate, in one local
transaction that also writes the `AttendanceRecorded` outbox row. A dropped enrollment stops working on the next request once its event is
applied. The one place a stale copy is not tolerated is a guardian's delegated access: other services confirm a "yes" with Academic over
`/internal/**` (shared secret, never routed by the gateway) through `GuardedCall` (timeout, circuit breaker, bulkhead), and refuse rather than
guess when Academic cannot answer ([data-ownership §3](contracts/data-ownership.md)).

**WebSocket.** After commit, Attendance broadcasts to `/topic/attendance/section/{id}`. Connection and subscription are authorized separately
(token on connect; per-destination on subscribe). The client connects through the gateway, which relays the WebSocket to Attendance without reading
the frames, so the service still decides who may connect and subscribe ([gateway.md](contracts/gateway.md) section 3a).

**Assessment and the grade event.** Assessment writes a new grade version and the `GradePublished` outbox row in one transaction; the outbox
publisher sends it to Kafka only after commit and marks it published after the broker acknowledges. Delivery is at-least-once, so every consumer
keeps a processed-event table and a redelivery is a no-op; the claim is idempotent consumers, never blanket exactly-once
([kafka-contracts](contracts/kafka-contracts.md)). Each message carries `event_id`, `event_type`, `event_version`, schema version, `correlation_id`,
`causation_id`, `tenant_id` and the W3C `traceparent` of the request that wrote it.

**Risk, advisor alert.** Risk still runs in the core process and reads grades, attendance and learning progress in process. A consumer in the
risk module reads `GradePublished` from `setu.grades` and `AttendanceSessionClosed` from `setu.attendance_sessions` (group `setu-risk`) and recalculates the student, or for a
closed session every student enrolled in its section, with no signed-in user: the student and
tenant come from the event's enrollment, the event id is recorded in `risk.processed_events` in the same transaction, and recalculations for one
student are serialised. The result is stored reproducibly (rule version, feature snapshot hash, calculation time, evidence ids), and an unchanged
snapshot records nothing. The `RiskScoreChanged` and `RecommendationCreated` events it writes carry the grade event's correlation id and name it
as their cause, so one trace follows grade to alert. Recommendation, intervention and the notification (Notification consumes the events into
per-person rows and pushes to a per-user topic) follow.

**AI explanation.** The AI service has no datasource; this is enforced by an architecture test, not a convention. It calls tools
(`getStudentAttendance`, `getAcademicProgress`, ...) that go through `AiFacts`; in a service layout `RemoteAiFacts` calls the data-holding
service with **the user's own bearer token**, so the data service's normal authorization applies to the AI's request exactly as it would to the
user's. The audit write fails closed. The chat is an async job (QUEUED, RUNNING, COMPLETED) announced on that user's own topic. Policy search
checks tenant and document ACL **before** ranking, and retrieved text is treated as untrusted. Model chain: Gemini, a second Gemini model, then
local Ollama as the last resort; each attempt is time-bounded and a failure is reported as unavailable, not as an empty answer.

**Observability.** One trace id follows the request from the gateway through every service, through the outbox row into the consumer's span
(`traceparent` in the outbox), and into log lines (`traceId` in the pattern; logs go to Loki over OTLP, spans to Tempo, metrics scraped by
Prometheus with a scrape token). Grafana provisions the three datasources and the Setu platform dashboard
([observability](contracts/observability.md)).

## 3. Rules that make the boundaries real

| Rule | How it is enforced |
|---|---|
| One owner per table | `table-owners.txt`, `TableOwnershipTests` |
| Frozen module-to-module edges | `module-contracts.txt`, `ModuleContractFreezeTests`, Spring Modulith `ModularityTests` |
| Cross-service foreign keys are listed, not accidental | `cross-service-foreign-keys.txt` (17 dropped for the extracted services; the rest stay until their modules move) |
| Every endpoint reaches its service | `GatewayRouteCoverageTests` |
| `ai-explain` has no datasource and no other module's repository | `AiExplainBoundaryTests` |
| A service starts with only its module and the kernel | `*ServiceWiringTests` with `ExtractedServiceWiringSupport` |
| Tenant never comes from the client | tenant resolved from the token; cross-tenant cases in `ExtractedServiceWiringSupport` and the per-module suites |
| Management endpoints are not public | `ActuatorGuardFilterTests`, `ManagementEndpointsTests` |

## 4. Deviations from the written plan, on purpose

- **The gateway is not Spring Cloud Gateway.** No Spring Cloud release train supports Boot 4.1; the gateway is a small servlet gateway behind the
  same route table and tests ([gateway §1](contracts/gateway.md)).
- **OpenFeign is not used.** Resilience4j's core libraries are used directly in `GuardedCall` (circuit breaker and bulkhead around a plain HTTP
  call); the only synchronous service-to-service calls are the guardian confirmation and the AI facts ([data-ownership §3](contracts/data-ownership.md)).
- **Record is not its own service.** The coupling audit found it thin enough to fold into Assessment, and it has not moved yet
  ([extraction-audit](extraction-audit.md)).

Where this layout is incomplete, and what comes next: [known-limitations-and-roadmap.md](known-limitations-and-roadmap.md).
