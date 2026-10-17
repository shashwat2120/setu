# Validation report (Sprint 6C.2)

What was run, what it showed, and what it cannot show. Targets are from canonical spec §14; sizing and method are in
[contracts/performance.md](contracts/performance.md). Date of the run: 2026-10-16.

## Method and limits, stated first

- **In-process.** Requests go through MockMvc into the real application context and the development PostgreSQL. There is no network between
  client and application and no gateway hop. Numbers are therefore a floor for the application and database work, not an end-to-end figure.
- **One machine, one database, one service process.** Windows development machine; embedded servers and the JDK HTTP client are unreliable on it,
  so a multi-process run of the extracted services was not possible. Cross-service behavior is covered by stubs and in-process tests (see below).
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

Full suite run in 20 chunks against one database, one chunk at a time, on the tree that contains the 6C.1 and 6C.2 changes: **all 20 chunks
passed, no failures.**

| Chunk | Tests |
|---|---|
| academic | 19 |
| aiexplain + aifacts (excluding AiChat*) | 73 |
| aiexplain AiChat* | 25 |
| assessment | 63 |
| attendance | 124 |
| audit | 39 |
| career | 25 |
| catalog | 63 |
| config | 36 |
| directory | 34 |
| enrollment | 73 |
| finance | 24 |
| gateway | 56 |
| guardianaccess | 7 |
| identity | 180 (1 skipped: the manual Argon2 benchmark) |
| institution | 35 |
| learning | 91 |
| notification (excluding the broker-backed Kafka test) | 37 |
| record | 8 |
| risk | 39 |
| shared kernel + `ModularityTests` | 124 |

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
| Trace context survives the outbox | `OutboxTraceParentTests`, `shared.observability` tests |
| Model call and embedder time out visibly; second Gemini model before Ollama | `AiChatSecondaryGeminiTests`, `SpringAiTextEmbedderTests` |

Defects this pass found are listed in [non-negotiables-test-audit.md](non-negotiables-test-audit.md) with their tests.

## Not validated

- A real multi-process run of the extracted services behind the gateway, with Kafka between them, and therefore Kafka consumer lag, the
  dead-letter path against a live broker, and the Grafana Kafka-lag panel.
- WebSocket traffic through the gateway (it answers 501 for upgrades; clients connect to the owning service).
- Load over a real network, or beyond the 40-scan burst.
- The cached dashboard aggregate's latency.
- Accessibility (no automated frontend test exists).
- Live Gemini behavior under quota exhaustion beyond what the opt-in `live-gemini` test covers.
