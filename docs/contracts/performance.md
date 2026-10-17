# Performance contract (Sprint 6C.2)

Canonical spec §14 sets the demo validation targets; this document says how the code is sized to meet them and which tests keep it that way.
These are **validation targets for the local demo, not SLAs**. Measurements are taken in-process (no network between client and application)
against the development database; see [validation-report.md](../validation-report.md) for the numbers and their limits.

## Targets and the tests that check them

| Target (spec §14) | Test | Run by |
|---|---|---|
| Authenticated reads and paginated lists < 500 ms (p95) | `DemoTargetsPerformanceTests.authenticatedReadsAreWellUnderHalfASecond` | `-Dtest=` on purpose (tag `performance`) |
| Enrollment command, full invariant chain, < 800 ms (p95) | `DemoTargetsPerformanceTests` (enrollment) | same |
| QR scan acknowledgement < 1 s, including a burst of simultaneous scans (p95 and max) | `DemoTargetsPerformanceTests` (burst) | same |
| No N+1: a list costs the same number of statements for 3 rows as for 20 | `QueryCountTests` | same |
| Page size is capped | `shared.web.PageSizeLimitFilterTests` | every build |
| The pool cannot deadlock | `config.ConnectionPoolSizingTests` | every build |

Timing tests carry the `performance` tag and are excluded from a plain `mvn test`: timings taken while other tests are running prove nothing.

## Connection pool sizing is a correctness rule

A write that uses a nested `REQUIRES_NEW` transaction (the insert guards that turn a lost uniqueness race into a clean answer; 43 classes use
one) holds its outer connection while the inner one is acquired, so **one request in flight needs two connections**. If more requests are in
flight than half the pool, every connection is held by an outer transaction waiting for an inner one and all of them stall until the connection
timeout. Measured here: 20 simultaneous QR scans against Hikari's default pool of 10 stalled for 30 s each.

The rule, enforced by `ConnectionPoolSizingTests`:

```
server.tomcat.threads.max x 2  +  background threads x 2  <=  spring.datasource.hikari.maximum-pool-size
```

| Setting | Default | Env var |
|---|---|---|
| Request threads (database-backed services) | 24 | `SERVER_MAX_THREADS` |
| Pool size | 64 | `DB_POOL_SIZE` |
| Idle connections kept open | 16 | `DB_POOL_MIN_IDLE` |
| Postgres `max_connections` (docker-compose) | 300 | |

24 x 2 + 8 x 2 = 64. Background threads (outbox publisher, schedulers, Kafka listeners) are budgeted at 8 and the test assumes that figure.
The gateway and the AI service have no datasource, so the rule does not apply to them and they run 200 threads (`SERVER_MAX_THREADS` in their
entry points). Requests beyond the thread count wait in the accept queue; they are not rejected.

**Idle connections.** A cold pool opens connections one at a time, and the first scans of a classroom burst paid for that. `minimum-idle: 16`
keeps enough open that a burst finds them ready. Ten services x 16 idle = 160, which `ConnectionPoolSizingTests` holds to at most 60 % of the
server's 300 so the services cannot starve each other or the migration/admin sessions. Raising `DB_POOL_MIN_IDLE` or the number of services needs
`max_connections` raised in step.

## Entry-point defaults

`SpringApplication.setDefaultProperties` has the lowest precedence of any property source, so a default for a key that `application.yml` also
sets is silently ignored. Until this pass every service's `server.port` and `spring.application.name` default was shadowed this way. Entry points
now set the yml's placeholders (`SERVER_PORT`, `APPLICATION_NAME`, `SERVER_MAX_THREADS`); `EntryPointDefaultsTests` reads the source and fails on
a shadowed key, and `EntryPointDefaultsPrecedenceTests` demonstrates the precedence rule itself.

## Page-size cap

`PageSizeLimitFilter` clamps a `size` parameter above `app.pagination.max-size` (default 100) down to the cap before the request reaches a
controller, so no client can ask for an unbounded page; the request is served with the capped size, not rejected. It is registered through `PageSizeLimitFilterConfig` rather than component scanning (a scanned filter was wrapped by the
Modulith tracing proxy and failed to initialise).

## Not claimed

- No load test over a network, and no multi-service run on this machine: embedded servers and the JDK HTTP client are unreliable on this
  Windows dev host, so verification is in-process (MockMvc, blocking-socket stubs, in-process STOMP).
- The burst margin is thin: the p95 of a 40-scan burst sits close to the 1 s target. It passes with warm connections; it would not with a cold pool.
- Cached dashboard aggregate (< 200-300 ms) is covered by `DashboardConsistencyTests` for correctness, not timed here.
