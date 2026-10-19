# Sprint 6 demo: run sheet (about 20 minutes)

What Sprint 6 added, in the order you can show it, with what to click, what to say and what proves it. Everything here runs on the nine-container
stack ([contracts/containers.md](contracts/containers.md)). Where a claim rests on a test instead of a live run, this sheet says so.

## Before anyone arrives (5 minutes)

1. **Stack up and healthy:** `docker ps` shows nine `setu-*-1` services as `healthy` (gateway, identity, academic, core, learning, assessment,
   attendance, notification, ai). From nothing: `docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.services.yml up -d`
   (about 4.5 minutes). To start from clean data: `pwsh infra/scripts/reset-demo.ps1 -Yes`, wait for "Done", then the command above.
2. **Frontend on the stack:** in `frontend/`, `npm run dev:stack`, then open `http://localhost:5180` (it must be 5180: the gateway's origin rule).
3. **Open these tabs:** the app twice (one window per role); Grafana `http://localhost:3001` (admin / admin); Prometheus targets
   `http://localhost:9091/targets`.
4. **Do not run `smoke-flow.js` before the demo.** It grades and completes `student.strong`'s CS101 on purpose. It is Act 4's live proof, run on stage.

Password for every seeded account: `SetuDemo#2025!` (seed data, marked as such).

| Role | Account | Used for |
|---|---|---|
| Faculty | `faculty.rao@demo.setu.local` (Ananya Rao) | live attendance, grading the final exam |
| Demo student | `student.average@demo.setu.local` (**Priya Nair**) | scanning, being graded, completing the course, the advisor |
| College admin | `admin.sit@demo.setu.local` | registrar view, institution-wide risk |

The six students of CS101-A, as the faculty **Grades** page lists them: Ishaan Kapoor = `student.strong` (the one `smoke-flow.js` grades, so leave him
for Act 4), **Priya Nair = `student.average`**, Rahul Verma = `student.attendance.risk`, Sneha Iyer = `student.assessment.risk`, Arjun Singh =
`student.learning.risk`, Meera Joshi = `student.intervention`.

## Act 1: what changed in Sprint 6 (2 minutes)

**Say:** Sprint 5 built one modular monolith. Sprint 6 split it along the boundaries the code already enforced, without rewriting it.
One codebase and one image now run as nine processes behind a gateway, talking through Kafka.

**Show:** the pictures `docs/images/architecture.png` and `docs/images/system-workflow.png` (open them full screen), with [architecture-walkthrough.md](architecture-walkthrough.md) beside them, then [extraction-audit.md](extraction-audit.md): the coupling audit
that decided the boundaries from measured coupling, not from guesses (6A.1). Then `docker ps`: nine services, PostgreSQL, Redis, Kafka,
Prometheus, Grafana, Tempo, Loki.

| Sprint 6 piece | One line to say | Where it is |
|---|---|---|
| 6A.2 Identity service | issues signed tokens and publishes its keys; every other service verifies them itself | [contracts/identity-service.md](contracts/identity-service.md) |
| 6A.3 Academic service | institution, catalog, enrollment: commands and queries, events out, no foreign database access | [contracts/academic-service.md](contracts/academic-service.md) |
| 6A.4 Gateway, discovery, config | one public address: CORS, token check, rate limit, correlation id, health-aware routing, a config server | [contracts/gateway.md](contracts/gateway.md), [contracts/config-server.md](contracts/config-server.md) |
| 6A.5 Learning, Assessment, Attendance | each decides from its own copy of the facts, kept current by events | [contracts/extracted-services.md](contracts/extracted-services.md) |
| 6B.1 Kafka contracts | every event versioned, idempotent consumers (processed-events table), retry then dead-letter | [contracts/kafka-contracts.md](contracts/kafka-contracts.md), [event-schemas.md](event-schemas.md) |
| 6B.2 Data ownership and resilience | one owner per table; synchronous calls only where unavoidable, with timeouts, circuit breakers and honest fallbacks | [contracts/data-ownership.md](contracts/data-ownership.md) |
| 6B.3 AI and notification services | the advisor and live notification are their own services; the secure tool boundary survives the network hop | [contracts/ai-service.md](contracts/ai-service.md), [contracts/notification-service.md](contracts/notification-service.md) |
| 6C.1 Observability | traces, metrics and logs that point at each other | [contracts/observability.md](contracts/observability.md) |
| 6C.2 Failure, security, performance | tests and live checks, with limits stated | [validation-report.md](validation-report.md) |

## Act 2: it is really running (2 minutes)

**Show:** Prometheus targets page: **9 of 9 up**. Grafana, dashboard **Setu: platform health**: requests per second, latency, sign-ins by
outcome, events published to Kafka by type, outbox backlog (events waiting for Kafka: should sit near 0).

**Say:** the contracts between modules are frozen by architecture tests (`module-contracts.txt`, `table-owners.txt`, `event-schemas.txt`): a change
that adds coupling fails the build.

## Act 3: the business flow in the browser (8 minutes)

**Optional, and the best way to show Kafka:** put a terminal next to the browser and run `pwsh infra/scripts/kafka-demo.ps1 follow`. It streams every event
as it is published (with its headers). When you publish the grade in step 3, a `GradePublished` event scrolls past in that terminal, then the events the
other services emit in reaction. `pwsh infra/scripts/kafka-demo.ps1 status` shows the outbox (rows written in the same transaction as the business change,
all `PUBLISHED`) and the message count per topic. Ctrl+C to stop it.

1. **Faculty, live attendance.** Window 1: sign in as `faculty.rao`. **Attendance** → **Open attendance session**. The browser asks to share this device's
   location: **Allow** (it becomes the classroom position). The page says **Live**, and "Classroom position: taken from this device".
   *Say:* that is a WebSocket through the gateway, relayed to the Attendance service; the service, not the gateway, decides who may subscribe.
2. **Student scan.** Window 2: sign in as `student.average`. **Attendance** → **Check in to a session**: paste the **Session ID** and the **Check-in token** shown
   on the faculty page (the token rotates every 30 seconds, so be quick or press **Rotate token** and copy the new one), **Check in**. The browser asks for the student's location: **Allow**, and the message says "Your location matches the classroom."
   Window 1: the student's row appears **PRESENT** without a refresh, with **Checked in from: In the room (N m)**.
   *Say:* location is a signal, not a gate. A student who declines, or checks in from far away, is still marked present, and the instructor sees the flag
   (**Away from the room (1.2 km)** or **Location not shared**). Only the outcome is stored, never the student's coordinates.
3. **Grade the final exam.** Window 1: **Grades** → section CS101-A → assessment **Final Exam** (seeded, open) → component **FINAL** → on **Priya Nair**'s row
   enter marks (say 78), a grade (B) and a grade point (3.0) → **Publish**. *Say:* the Assessment service wrote the grade and an outbox row in one
   transaction; Kafka carries it from there. The Final Exam's component is marked as the course's final grade, which is why this one grade ends the course.
4. **What happens by itself (a few seconds).** Window 2, **Grades**: CS101 turns **COMPLETED** with its **Final grade** (the Academic side completed the
   enrollment from the event); the **bell** shows a notification (Notification service); **Student Success** shows the risk recalculated with no one pressing
   a button (Risk consumed the same event). *Say:* three services reacted to one event, each idempotently.
5. **The advisor.** Window 2, **AI Advisor**: ask "how am I doing this term?" The answer comes from Gemini through the application-owned tools,
   scoped to this student, with its evidence; it explains, it does not decide anything.

## Act 4: one request across services (2 minutes)

**Run (live proof, about 7 seconds, 11 with the advisor):** `AI_CHECK=1 node infra/scripts/smoke-flow.js` prints 17 PASS lines: sign-in, attendance
session and scan, the grade, the enrollment completing about 1.6 seconds later through Kafka, risk recalculated, the student notified, the transcript, and
the advisor's answer. (It uses `student.strong` / Ishaan Kapoor, not the demo student, and reuses the seeded Final Exam's final-grade component.)

**Show one trace:** Grafana (`http://localhost:3001`) → **Explore** → data source **Tempo** → query type **TraceQL** → run
`{ rootName = "http post" && resource.service.name = "gateway" }` → open the longest result (the grade publish, about 1.7 s). It holds about 30 spans in the
gateway, Assessment, Academic, Learning, Attendance, Notification and the core (including the Student Success risk listener): the request and the
Kafka event it caused, as one story. The correlation id is in the logs of every service (Grafana → Explore → Loki).

## Act 5: failure, honestly (3 minutes)

```
docker stop setu-assessment-1
```
Reload the faculty **Assessments** page (it errors) while **Attendance** and sign-in still work. Or from a terminal (bash):
```
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' -d '{"email":"faculty.rao@demo.setu.local","password":"SetuDemo#2025!"}' | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s).accessToken))")
curl -s http://localhost:8080/assessment/assessments?sectionId=00000000-0000-0000-0000-000000000000 -H "Authorization: Bearer $TOKEN"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/identity/me -H "Authorization: Bearer $TOKEN"
```
The first answers **502** `BAD_GATEWAY` ("the assessment service could not be reached") in the standard error envelope: not a hang, not invented data. The
second still answers 200. Then bring it back (under a minute):
```
docker start setu-assessment-1
```
(Checked on this stack: 502 with Assessment stopped; identity and attendance answered 200.)
*Say:* the same holds for Kafka: stop it during a grade and nothing is lost, because the outbox keeps the event until the broker returns
(shown live in the validation run).

## Act 6: evidence and honest limits (2 minutes)

- [validation-report.md](validation-report.md): 21 test chunks, about 1,260 automated tests, all green on the last full run (the gateway chunk was
  re-run green after the WebSocket relay); demo targets measured (list/read p95 47 ms vs 500 ms target; scan burst p95 942 ms vs 1 s target, thin margin).
- [non-negotiables-test-audit.md](non-negotiables-test-audit.md): every non-negotiable has a test, and the defects found are listed.
- [known-limitations-and-roadmap.md](known-limitations-and-roadmap.md): what is not done, stated plainly. Do not skip this slide.

## If something goes wrong

| Symptom | Fix |
|---|---|
| A service is `unhealthy` or restarting | `docker logs --tail 50 setu-<name>-1`; `docker restart setu-<name>-1` (under a minute; the core skips seeding on restart) |
| Login works but pages are empty / 401 | the frontend must be on port 5180, started with `npm run dev:stack` |
| The board does not say **Live** | reload the page; `LIVE=ws://localhost:8086/ws node infra/scripts/smoke-websocket.js` tells a gateway problem from a service problem |
| The scan is refused | the token expired (30 s): press **Rotate token** and paste the new one |
| The advisor is slow | the first call after start-up can take about 5 to 20 seconds; Gemini is primary, a local model is the last-resort fallback |
| Everything is wrong | `pwsh infra/scripts/reset-demo.ps1 -Yes`, wait for "Done", then `docker compose ... up -d` (about 5 minutes) |

## Questions you may get

- **Why not Spring Cloud Gateway?** No Spring Cloud release train supports this Spring Boot version; the gateway is a small servlet application behind the
  same contract, so moving later replaces one class ([contracts/gateway.md](contracts/gateway.md) section 1).
- **Is it exactly-once?** No, and the docs never claim it: at-least-once delivery with idempotent consumers.
- **What is still in the core process?** Record, risk, career, finance, onboarding ([known-limitations-and-roadmap.md](known-limitations-and-roadmap.md)).
- **Is it ready for load?** Single-user timings on one machine; load behaviour is listed as not validated.
