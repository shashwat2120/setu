# Validation report (Sprint 6C.2)

What was run, what it showed, and what it cannot show. Targets are from canonical spec §14; sizing and method are in
[contracts/performance.md](contracts/performance.md). Date of the run: 2026-10-16.

## Method and limits, stated first

- **In-process.** Requests go through MockMvc into the real application context and the development PostgreSQL. There is no network between
  client and application and no gateway hop. Numbers are therefore a floor for the application and database work, not an end-to-end figure.
- **One machine, one database.** The Windows development machine; embedded servers and the JDK HTTP client are unreliable on it, so these timings
  and the test suite are in-process. The distributed layout was run for real, as nine containers on Linux, and is reported in the next section.
- **Local, shared hardware.** Timings were taken with nothing else running (a first attempt while another test run held the database hung and
  was discarded). They will vary between runs; the burst figure in particular varies by about 10 %.
- **Validation targets, not SLAs** (spec §14).

## Demo validation targets

Command: `-Dtest=DemoTargetsPerformanceTests,QueryCountTests -DexcludedGroups=live-ollama` (tag `performance`, excluded from a plain build).

| Target (spec §14) | Measured | Result |
|---|---|---|
| Authenticated paginated list, < 500 ms | n=40, median 34 ms, p95 47 ms, max 67 ms | met |
| Authenticated single read, < 500 ms | n=40, median 30 ms, p95 47 ms, max 61 ms | met |
| Enrollment command (full invariant chain), < 500-800 ms | n=20, median 40 ms, p95 45 ms, max 112 ms | met |
| QR scan acknowledgement, < 1 s, burst of 40 simultaneous scans served by 24 workers | median 736 ms, p95 942 ms, max 942 ms | met, thin margin |
| No N+1 on a list | 7 SQL statements for 3 rows and for 20 rows | met |
| AI is asynchronous (queued / running / completed) | status events covered by `AiServiceWiringTests` and `AiJobSubscriptionAuthorizerTests`, not timed | met (behavioral) |
| Cached dashboard aggregate, < 200-300 ms | not timed; correctness only (`DashboardConsistencyTests`) | **not measured** |

Reading the burst result honestly: the single scan and the sequential commands are far inside their targets, but 40 scans arriving together
queue behind 24 request threads and a pool of 64 connections, and the slowest finishes at 942 ms against a 1000 ms target. Two things keep it
there: the pool keeps 16 connections open (a cold pool measured worse) and the pool is sized so nested transactions cannot deadlock (the
unsized default stalled 20 scans for 30 s each). A larger classroom, a slower disk or a busier database would push it over. The knobs are
`SERVER_MAX_THREADS`, `DB_POOL_SIZE` and `DB_POOL_MIN_IDLE`, bounded by the rule in `ConnectionPoolSizingTests`.

## Regression

The full suite was run in 21 chunks against one database, one chunk at a time, twice: once on the tree of 6C.1/6C.2 (before the grade-event
consumer) and once on the tree that includes it. **All chunks passed in both runs, with one exception that the second run found and that was fixed:**
the shared-kernel chunk had one test error, a Logback rolling-file clean-up timeout while an application context started. The cause was a defect
from 6C.1: the logging configuration included Spring Boot's `base.xml`, which always attaches a rolling `spring.log` in the system temp directory.
Logs now go to the console and OpenTelemetry only, a file is opt-in (`file-logs` profile), and `LoggingSystemTests` pins it. The shared-kernel,
`ModularityTests` and config suites were rerun after the fix and passed. The other 20 chunks of the second run had passed before the fix; the fix
touches only the logging configuration and was not followed by a third full run.

A third full run (21 chunks) was made on the tree that includes "publishing a final grade completes the enrollment" (commit `1ad3fca`). Nineteen
chunks passed the first time. Two did not, neither because of the code:

- **`risk`**: one error, `RiskHttpTests`, "Time elapsed: 80849 s". The machine was suspended in the middle of the run (about 22 hours), its
  database connections were closed and the pool timed out waiting. Rerun on its own: all 60 tests pass.
- **`AiChat*`**: one error, `AiChatIntegrationTests.ignoresAnInstructionEmbeddedInsideARetrievedPolicyDocument`, "timed out (primary budget 60s,
  overall 60s)". That test makes the live Gemini model call a tool and answer within 60 seconds, and the model was slower than that on two runs in a
  row after answering in time earlier. With the budget raised to 180 s it passes, the model taking 69 s. It is a test that depends on live-model
  latency, not on anything the platform does; the other 24 tests of the chunk passed.

A fourth full run (21 chunks) followed the work of running the services as containers (the entry-point fixes, the WebSocket handshake, the seeder
restart guard, the test suite's own Kafka topic prefix, the Prometheus targets). **All 21 chunks passed the first time, with no error and no rerun.**
The counts below are from it.

| Chunk | Tests |
|---|---|
| academic | 19 |
| aiexplain + aifacts (excluding AiChat*) | 73 |
| aiexplain AiChat* | 25 |
| assessment | 73 |
| attendance | 125 |
| audit | 39 |
| career | 25 |
| catalog | 63 |
| config | 39 |
| directory | 34 |
| enrollment | 106 |
| finance | 24 |
| gateway | 56 |
| guardianaccess | 7 |
| identity | 180 (1 skipped: the manual Argon2 benchmark) |
| institution | 35 |
| learning | 92 |
| notification (excluding the broker-backed Kafka test) | 37 |
| record | 20 |
| risk (includes the grade-event consumer: 21 tests) | 60 |
| shared kernel + `ModularityTests` | 125 |

Excluded by design: tag `live-ollama` (needs a local model), tag `live-gemini` (needs a key and spends embedding quota), tag `performance` (run
above, separately), and `NotificationKafkaConsumerTests`, which cannot open an NIO selector on this machine (its retry/DLQ policy is covered by
`NotificationErrorHandlerTests`). The chunks were run from a clean copy of the tree, so a passing result does not depend on stray local files.

## Failure, security and observability checks added in 6C

| Area | Evidence |
|---|---|
| Service unavailable: honest "Unavailable", no invented data, circuit breaker and bulkhead | `GuardedCallTests`, `GuardianGrantVerificationTests`, AI remote-facts tests |
| Gateway does not expose management endpoints | `ActuatorGuardFilterTests` |
| Services expose only health, info and a token-guarded Prometheus scrape | `ManagementEndpointsTests` |
| Page size capped | `PageSizeLimitFilterTests` |
| Tenant isolation across the extracted services' read models | cross-tenant cases in `ExtractedServiceWiringSupport` |
| Entry-point defaults are not silently shadowed | `EntryPointDefaultsTests`, `EntryPointDefaultsPrecedenceTests` |
| Risk recalculates on a grade event; idempotent, tenant-checked, one at a time per student, refusals leave nothing | `RiskEventProcessorTests`, `RiskEventListenerTests`, `RiskErrorHandlerTests`, `RiskConsumerWiringTests` (without a broker) |
| Logs go to console and OpenTelemetry, no file unless asked for | `LoggingSystemTests` |
| A final grade completes the enrollment; a failing grade earns no credit; grading carries on after completion | `FinalGradeEventProcessorTests`, `FinalGradeFlowTests`, `FinalGradeComponentTests`, `FinalGradePrerequisiteTests`, `RecordDirectoryTests` |
| Trace context survives the outbox | `OutboxTraceParentTests`, `shared.observability` tests |
| Model call and embedder time out visibly; second Gemini model before Ollama | `AiChatSecondaryGeminiTests`, `SpringAiTextEmbedderTests` |

Defects this pass found are listed in [non-negotiables-test-audit.md](non-negotiables-test-audit.md) with their tests.

## The distributed layout, run for real

Nine containers (the gateway, Identity, Academic, Learning, Assessment, Attendance, Notification, the advisor and the core) behind the gateway,
with PostgreSQL, Redis, Kafka, Prometheus, Tempo, Loki and Grafana, on Linux ([contracts/containers.md](contracts/containers.md)). Reference flow of
canonical spec section 16, through the gateway, over HTTP, from a clean database: **19 of 19 steps** (the 18 data steps in 7 to 11 seconds across runs, and the advisor's answer about 5 seconds more),
including the enrollment completing 1.6 s after the grade was published, by an event travelling from one process through Kafka to another; the
live attendance board updating 141 ms after a scan over a real WebSocket; Prometheus scraping 9 of 9 targets; consumer lag 0 and no dead letters;
a trace from the gateway through Assessment and through Kafka into five other services; a service stopped (the gateway answers 502 honestly, the
rest carry on) and Kafka stopped during a grade (nothing lost, everything follows within seconds of the broker's return).

These are single-user timings on one machine, not load figures.

## Not validated

- Kafka consumer lag **under load**, and the dead-letter path with a real poison event on the real stack (the policy itself is tested without a broker).
- WebSocket traffic through the gateway (it answers 501 for upgrades; clients connect to the owning service).
- Load over a real network, or beyond the 40-scan burst.
- The cached dashboard aggregate's latency.
- Accessibility (no automated frontend test exists).
- Live Gemini behavior under quota exhaustion beyond what the opt-in `live-gemini` test covers.
