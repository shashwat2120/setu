# The services as containers (Sprint 6C.3 follow-up)

Until now the extracted services had only ever been run **in-process**: the Windows development machine cannot run an embedded server, Netty or the
JDK HTTP client reliably (a JDK NIO defect), so every claim about the distributed layout rested on MockMvc, blocking-socket stubs and in-process
STOMP. This page is the first time they run as what they are: nine separate processes behind the gateway, with Kafka, Redis and PostgreSQL between
them, on Linux, in containers.

## Running it

```
docker build -f backend/Dockerfile -t setu/backend:dev .                      # one image for every service; built from the repository root
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.services.yml up -d
node infra/scripts/smoke-flow.js            # the reference flow of canonical spec section 16, through the gateway, over HTTP (AI_CHECK=1 adds the advisor)
node infra/scripts/smoke-websocket.js       # the live attendance board over a real WebSocket
pwsh infra/scripts/reset-demo.ps1           # start over: drop the database, clear this stack's Kafka topics and Redis announcements
```

The gateway is `http://localhost:8080`; each service is also published on `127.0.0.1` at its own port (identity 8081, academic 8082, core 8083,
learning 8084, assessment 8085, attendance 8086, notification 8087, ai 8088). Grafana is `http://localhost:3001`, Prometheus `:9091`.

**The React frontend in front of it.** `npm run dev:stack` (in `frontend/`, or the `setu-frontend-stack` launch configuration) serves the app on
`http://localhost:5180` with every request, the live attendance board's WebSocket included, proxied to the gateway, which relays it to the Attendance
service. Port 5180 matters: it is one of the origins the gateway's CORS rule allows. Checked
in a browser against this stack: sign-in through the gateway, the faculty Assessments page (final-grade checkbox, and the one-final-grade-per-section
refusal shown to the user), the student Grades page (CS101 completed, final grade B, 4 of 160 credits) and the faculty live attendance board (a
student's scan appeared on it without a refresh), with no console or network errors apart from the expected 401 before sign-in.

**First time.** Put the stack's secrets in the git-ignored `infra/docker/.env` (`.env.example` says which and how to make them): `JWT_SIGNING_KEY`
(Identity's token-signing key), `JWT_PUBLIC_KEY` (its public half), `SETU_INTERNAL_TOKEN` (service-to-service calls the gateway never routes),
`METRICS_SCRAPE_TOKEN` (must equal the contents of `infra/docker/observability/scrape-token.local`, which Prometheus reads), and optionally
`GEMINI_API_KEY` (without one the advisor starts on the local Ollama model and says so).

## What runs

One image (`setu/backend`, 751 MB: a Java 25 runtime and the 138 MB application jar, which holds every module, plus the database migrations). Which
service a container is, is decided by `SETU_MAIN`, the entry-point class it starts. Memory figures are measured under the demo workload.

| Container | Entry point | Memory (used / ceiling) | Role |
|---|---|---|---|
| `gateway` | `GatewayApplication` | 260 MiB / 512 | the one public address; no database |
| `identity` | `IdentityServiceApplication` | 640 MiB / 768 | accounts, passkeys, the only holder of the signing key; password hashing needs the memory |
| `academic` | `AcademicServiceApplication` | 430 MiB / 640 | institution, catalog, enrollment, directory, guardian access |
| `learning`, `assessment`, `attendance`, `notification` | their `*ServiceApplication` | 400-420 MiB / 640 | each decides from its own copy of Identity's and Academic's facts |
| `ai` | `AiServiceApplication` | 300 MiB / 512 | the advisor; **no datasource**, reads facts through the gateway with the user's own token |
| `core` | `BackendApplication` | 1.2 GiB / 1.6 GiB | the modules not yet extracted (record, risk, career, finance, onboarding); the only process that migrates the database and seeds the demo college |
| `db-init` | psql | one-shot | creates the services' database (`setu_demo`) once |

The core is the monolith, so it never loads the remote token verifier. It verifies the tokens Identity signs with Identity's **public** key alone (the
"previous public keys" of a key rotation), so the private signing key lives in one container. The "ephemeral signing key" warning in the core's log
is that design, not a mistake.

Starting from nothing takes about four and a half minutes: the database is created, 91 migrations apply in about 3 seconds, the demo college is seeded
(about 30 seconds), and the services then boot (about 90 seconds each when started together on 8 CPUs; the gateway takes 15). A restart of any one of
them takes under a minute; the core, whose database outlives it, skips seeding.

## What was verified, live

| Claim | How it was checked | Result |
|---|---|---|
| 91 migrations install on an empty database | the core's first start | applied in 3.3 s |
| Every service starts and is healthy; none restarts, none is killed | 9 containers, several full resets | zero errors in any log; the only warnings are Kafka noting a topic was just created, and the core's ephemeral-key notice |
| One token, issued by Identity, is accepted by every service | a sweep of every parameter-free GET of the platform through the gateway | no 401, no 5xx |
| The reference flow works across processes (spec section 16) | `smoke-flow.js` | 19 of 19 steps: attendance session and scan, an assessment with a final-grade component, the grade, the enrollment completing 1.6 s later through Kafka, risk recalculated, the student notified, the transcript updated, and the advisor answering |
| The live board updates over a real WebSocket, through the gateway | `smoke-websocket.js` (the gateway relays `/ws` to Attendance) | 8 of 8 steps: the update arrives 289 ms after the scan (141 ms connecting to Attendance directly); no token and a student on the faculty board are both refused by the service, and the refusal reaches the client; an upgrade on a non-live path is a 501 |
| The relay says so honestly when the service is gone | Attendance stopped, then a handshake through the gateway | 504 with the error envelope (the stopped container's address did not answer within the 2 s connect timeout); 101 again once it was back. A browser (Vite dev server in front of the gateway) showed "Live" and a scanned student appeared on the board without a refresh |
| The read models in the services are fed by events from other processes | the flow above: Attendance, Assessment and the rest decide from their own copies, filled from events the core wrote while seeding | works |
| A trace follows one request across processes and through Kafka | Tempo: the grade request's trace holds spans from the gateway, Assessment, Notification, Learning, Attendance and the core | works |
| Prometheus reaches every service with the scrape token | the targets page | 9 of 9 up |
| The Grafana panels have live data behind them | every panel query run against the live Prometheus | 19 of 25 return data; the 6 empty ones are failure counters that never fired (nothing failed) and two features the smoke tests do not use (asynchronous advisor jobs, guardian confirmation) |
| Consumer groups keep up and nothing is dead-lettered | `kafka-consumer-groups` after the flow | lag 0 in all 8 groups, no dead-letter topic |
| A service that is down is reported honestly and the rest carry on | Assessment stopped | the gateway answers `502 BAD_GATEWAY` with the standard envelope and no instance address; Academic keeps answering 200; Assessment serves again about a minute after it restarts |
| The transactional outbox loses nothing when Kafka is down | Kafka stopped, a grade published, Kafka restarted | the grade write succeeds (HTTP 200), 4 events wait as `NEW` in the database, and about 15 s after the broker returns the enrollment, risk, notification and transcript all follow |
| A restart does not re-seed or crash | the core recreated against its seeded database | healthy in 46 s, seeding skipped |

## What running it for real found

Nine defects, none of which any test could see, because every test ran in one process. Each is fixed, and each has a test or a check that now fails
without the fix.

1. **Every extracted service failed to start.** Spring Modulith's actuator endpoint needs a runtime bean that every service entry point deliberately
   excludes, so each died at start-up. No in-process test builds that endpoint. (`EntryPointDefaultsTests`.)
2. **No WebSocket of any extracted service could connect.** The remote security chain rejected the HTTP handshake with a 401; the monolith's chain
   opens `/ws` because the real boundary is the STOMP CONNECT frame. In-process STOMP tests do not go through the HTTP chain.
   (`ExtractedServiceWiringSupport`, and `smoke-websocket.js`.)
3. **The image had no database migrations.** Flyway looks for them at a path relative to the source tree. They now ship in the image, which is why the
   build is run from the repository root.
4. **The demo seeder crashed any restart.** It began again with the first account and died on "already exists". It now records a completion marker at
   the very end and skips when it is there; data without the marker still fails loudly. (`DemoDataSeederRestartTests`.)
5. **The core could not run in remote token mode.** A combination nobody had run. The contract says the monolith never loads the remote verifier; the
   deployment gives it Identity's public key instead.
6. **Kafka advertised itself as `localhost`,** so no container could use it. The broker has a second listener under its container-network name.
7. **Every service tried to push metrics to `localhost:4318` every minute.** The OTLP metrics registry was on by default; the design says metrics are
   scraped, never pushed. Off now.
8. **Services that never use AI created a model client and warned about a missing key.** They now start none (`EntryPointDefaultsTests`).
9. **The test suite and a running stack shared one Kafka and one Redis.** Test events reached the stack's consumers and were dead-lettered; the
   stack's announcements in Redis misled a gateway test. The tests now publish under their own topic prefix (`setu-test.`). The Redis one remains:
   do not run `GatewayWiringTests` while the stack is up.

## Limits that remain

- **One machine, one broker, one database server.** Nothing here is highly available. TLS is not terminated anywhere.
- **Only one live route goes through the gateway** (`/ws`, the live attendance board, relayed to Attendance). Live delivery from Notification and job status from the AI service have no route yet: an upgrade there is a 501 and those clients connect to the service directly, on its own port.
- **Start-up is slow** (about 90 s per service when all start at once) and the whole stack wants about 8 GB; 12 GB is comfortable if everything peaks.
- **Start-up order is by health, not by readiness for traffic:** a service reports healthy when its context is up, before its Kafka consumer has caught up.
- **The config server is not in the stack,** and secrets are in an environment file. SpringDoc's `/v3/api-docs` is on (it is how the route-coverage test
  reads the endpoints).
- **The build runs Maven inside the image,** so the first build downloads the dependencies (several minutes); later builds reuse the cache.
