# Final demo: the §16 business flow (Sprint 6C.3)

Canonical spec §16:

```
Passkey login -> academic dashboard -> live QR attendance -> authenticated scan
-> backend validation -> WebSocket update -> assessment -> grade event
-> risk recalculation -> advisor alert -> AI evidence-grounded explanation
-> Grafana -> Sprint 6 distributed architecture walkthrough
```

This page says, stage by stage, **what you can click through, which service answers it in the distributed layout, and what proves it.** It does
not claim a stage works end to end where the evidence is a test and not a live run: the right-hand column is explicit about which it is.

Two ways to present it, and why there are two:

1. **Live, one process (the UI walkthrough).** The full application on the development bridge with the React app, using the seeded accounts
   ([demo-script.md](demo-script.md) for setup, [demo-steps.md](demo-steps.md) for the click-by-click). Every stage below that has a UI is shown here.
2. **Distributed, by evidence (the architecture walkthrough).** The same flow, routed across the extracted services: [architecture-walkthrough.md](architecture-walkthrough.md).
   A multi-process run of those services could **not** be performed on the development machine (embedded servers and the JDK HTTP client are
   unreliable on this Windows host), so the distributed behavior is shown through the wiring, contract and failure tests, not a live
   multi-service session. [validation-report.md](validation-report.md) states the same limit.

## Stage by stage

"Service" is the one that owns the stage once extracted; "core" is what still runs in the monolith process
(record, risk, career, finance, onboarding).

| # | Stage (spec §16) | Where to see it | Service (gateway route) | Evidence |
|---|---|---|---|---|
| 1 | Passkey login | Login page, **Sign in with a passkey**; register one first under **Security** ([demo-script §1](demo-script.md)) | Identity (`/auth/**`) | `PasskeyFullFlowTests` (ceremony, server side), password path and rate limit in `identity` suite. **Not exercised with a real authenticator in the automated runs**; needs Windows Hello or a security key on the demo machine |
| 2 | Academic dashboard | `admin.sit@demo.setu.local` lands on the registrar dashboard; student and faculty dashboards | Academic (`/institution`, `/catalog`, `/enrollment`, `/directory`, `/guardian-access`) plus core | `academic`, `directory`, `enrollment`, `catalog` suites; authenticated reads and lists p95 47 ms against a 500 ms target ([validation-report](validation-report.md)). No test compares the dashboard tiles with a hand count; they are live queries |
| 3 | Live QR attendance | Faculty: **Attendance -> Open attendance session**; the token rotates | Attendance (`/attendance/**`) | `attendance` suite (124 tests); rotating signed token, no student PII in it |
| 4 | Authenticated scan | Student scans the token | Attendance | Scan validated for token, tenant, expiry, enrollment, duplicate; `AttendanceConcurrencyTests` (duplicate scan); acknowledgement p95 942 ms for a burst of 40 (target < 1 s) |
| 5 | Backend validation | Same request: a wrong, expired, replayed or other-college token is refused with a reason | Attendance, deciding from its own read-model copy of enrollment | `AttendanceServiceWiringTests` (decides from the local copy; a dropped enrollment stops working on the next request) |
| 6 | WebSocket update | Faculty session page shows the new record | Attendance serves STOMP itself (`/topic/attendance/section/{id}`); clients connect to the service directly, the gateway answers an upgrade with 501 | `AttendanceStompAuthorizationTests` (the real STOMP stack in-process, per-connection and per-destination authorization). **Cannot be watched live on the development bridge** (no WebSocket upgrade); the page says so instead of faking it |
| 7 | Assessment | Faculty creates, opens and evaluates a quiz; the student attempts it ([demo-steps §3-5](demo-steps.md)) | Assessment (`/assessment/**`) | `assessment` suite (63 tests), `AssessmentConcurrencyTests` (concurrent grade update); answer keys never sent to students |
| 8 | Grade event | **Grades -> Publish**; `.\infra\scripts\kafka-demo.ps1 follow` shows `GradePublished` | Assessment writes the event with the grade in one transaction (outbox) | `OutboxPublisherTests`, `EventEnvelopeTests` / `EventConsumerContractTests` (envelope headers, versions), grades are versioned and append-only |
| 9 | Risk recalculation | Publishing the grade triggers it: the risk consumer reads `GradePublished` and recalculates that student (no one signed in; the audit entry names the event). **Student Success -> Recalculate** is the manual path | core (risk) | `RiskEventProcessorTests` (idempotent, tenant checked, no actor, serialised per student, refusals leave nothing), `RiskEventListenerTests` (one flow from grade to alert), `RiskErrorHandlerTests`, `RiskConsumerWiringTests`; `RiskCalculationTests`, `RiskScoringEngineTests` (reproducible: rule version, feature-snapshot hash, calculation time stored). **The consumer itself was verified without a broker** (processor and listener called directly with the real outbox payload); consuming from a live broker is the same unverified-here limit as the other Kafka consumers |
| 10 | Advisor alert | A MEDIUM/HIGH student gets a proposed recommendation; **Accept** opens an intervention; the bell lists the notification | core (risk) -> Notification (`/notifications/**`) | `RecommendationInterventionTests`, `notification` suite (37 tests; events become notification rows; retry then dead-letter policy in `NotificationErrorHandlerTests`) |
| 11 | AI evidence-grounded explanation | Student **AI Advisor**: "Why is my risk band high and what should I do first?" The answer cites the facts it used and the policy passages it retrieved | AI (`/ai/**`), which has **no datasource**; it reads facts through the secure tool boundary with the signed-in user's own token | `AiToolAuthorizationTests`, `PolicySearchRetrievalTests` (access check before ranking), `AiChatIntegrationTests` (prompt injection in a retrieved document is ignored), `AiServiceWiringTests` (QUEUED, RUNNING, COMPLETED job events; refusal when the data service is unreachable), `AiExplainBoundaryTests` |
| 12 | Grafana | `http://localhost:3001` (admin / admin locally), dashboard **Setu platform**; Tempo for traces, Loki for logs | all | Dashboard and datasources provisioned from files; `DashboardConsistencyTests` checks that every meter a panel names is produced by the code and that Prometheus scrapes every service entry point's port. **Panels were verified against the metrics the code emits, not against a live multi-service scrape** |
| 13 | Architecture walkthrough | [architecture-walkthrough.md](architecture-walkthrough.md) | gateway, 9 service entry points | `ModuleContractFreezeTests`, `TableOwnershipTests`, `GatewayRouteCoverageTests` |

## Suggested order and timing (about 12 minutes)

1. (1 min) State what is real and what is not: the table above, especially rows 1, 6, 9 and 12.
2. (2 min) Rows 1-2: sign in, show the dashboard counts are live.
3. (3 min) Rows 3-6: faculty opens a session, a student scans, show a refused scan (expired or reused token), explain the WebSocket limit and point at the test.
4. (2 min) Rows 7-9: take the quiz, evaluate, publish the grade, show `GradePublished` on the broker, press **Recalculate** and show the stored rule version and snapshot hash.
5. (1 min) Row 10: accept the recommendation, show the notification.
6. (1 min) Row 11: ask the advisor; ask something unrelated to the student's data to show the refusal; mention the model chain (Gemini, a second Gemini model, then local Ollama).
7. (2 min) Rows 12-13: Grafana, then the architecture walkthrough.

## Before presenting

- Run the stack and check the outbox is draining (`kafka-demo.ps1 status`); on this machine the Kafka client can take minutes to start (see [demo-script §9](demo-script.md)).
- `GEMINI_API_KEY` set in `backend/.env.local` for the advisor; without it the run starts on the local model and says so.
- For Grafana: `pwsh infra/docker/observability/init-scrape-token.ps1`, then bring the stack up ([infra/docker/README.md](../infra/docker/README.md)).
- Do not present the seeded numbers as real: they are marked as example data.
