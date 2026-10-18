# Observability (Sprint 6C.1)

One question matters when a request goes wrong across services: *where did it go, how long did each step take, and what did each service say
about it?* The answer is three signals that point at each other: **metrics** (Prometheus), **traces** (Tempo) and **logs** (Loki), shown in
Grafana, all carrying the same identifiers.

| Signal | Produced by | Stored in | How it leaves a service |
|---|---|---|---|
| Metrics | Micrometer | Prometheus | scraped from `/actuator/prometheus` with the scrape token |
| Traces | Micrometer Tracing + OpenTelemetry | Tempo | pushed over OTLP/HTTP when `OTEL_TRACES_EXPORT_ENABLED=true` |
| Logs | Logback via the OpenTelemetry appender | Loki | pushed over OTLP/HTTP when `OTEL_LOGS_EXPORT_ENABLED=true` |

Nothing is sent anywhere unless export is switched on, and the stack is optional: a service runs the same with Tempo and Loki absent.

## 1. Identifiers that tie the signals together

* **Trace id / span id** (W3C): every request, Kafka listener and observed outbound call is a span. The ids are in the MDC, so every log line
  carries `[<traceId>-<spanId>]`; over OTLP, Loki stores them as `trace_id` / `span_id` and Grafana links a log line to its trace and back.
* **Correlation id** (`X-Correlation-Id`, `correlation_id` on events): the *business flow* (kafka-contracts.md section 4). It crosses places a trace
  cannot follow cleanly (an event published a while after the request) and is in every log line too. Use the trace for "what did this request do",
  the correlation id for "everything this flow caused".
* **`application` label** on every metric (the service's `spring.application.name`) and the `service` label Prometheus adds per target.

## 2. How a trace crosses each kind of hop

| Hop | Mechanism |
|---|---|
| Client -> gateway, gateway -> any service | the gateway's `ApacheUpstream` runs each call in `Outbound.call` (an observation, so a span and a timer) and sends the `traceparent` of that span, replacing any the client sent; the service's server instrumentation continues it |
| AI service -> data service (`/ai-facts/**`) | `RemoteAiFacts`, the same way (`peer=data-service`) |
| Any service -> Academic (guardian-grant check) | `RemoteGuardianGrantVerifier`, the same way (`peer=academic`) |
| Request -> event -> consumers | `OutboxEventWriter` stores the writing span's `traceparent` with the row (`trace_parent`, V87, validated by a CHECK); `OutboxPublisher` sends it as the standard `traceparent` header **only if well formed**; listener observation is on, so each consumer's span joins the trace of the request that caused the event, even though the event was published later by another thread |

`traceparent` is an optional header, outside the frozen envelope (`schema_version` stays 1): a consumer that ignores it loses nothing.
Hand-built HTTP clients are why `Outbound` exists; anything created through Spring's own client builders is instrumented already.

## 3. Metrics

Every service exposes `/actuator/prometheus`, **never publicly**: it requires the one dedicated scrape token (`app.metrics.scrape-token`), compared
in constant time, closed to everyone when none is configured, and not reachable with any user's token. The filter (`MetricsScrapeAuthFilter`) is
in the shared kernel and used by Identity and by every other service's security chain (`AcademicServiceWiringTests`).

Business and platform meters, all in the **Setu: platform health** dashboard (`infra/docker/observability/grafana/dashboards/setu-platform.json`):

| Meter | Says |
|---|---|
| `http.server.requests` | request rate, errors and p95 latency per service |
| `setu.gateway.requests{route,outcome}` | what the edge did: proxied, refused, rate limited, no route, upstream unreachable or slow |
| `setu.outbound{peer,operation}` | latency of calls between services |
| `setu.sync_call.unavailable{call,reason}` | `circuit_open`, `bulkhead_full` or `failed` for a synchronous call |
| `setu.guardian.grant_verification{outcome}` | `active`, `not_active` (the copy was behind a revocation) or `unavailable` (access refused) |
| `setu.reconciliation.orphans{service,reference}` | ids a service holds that its copy of the owner's data does not know |
| `setu.outbox.pending`, `.events.published{event_type}`, `.events.failed{event_type}` | events written but not yet on Kafka; throughput; publisher trouble |
| `setu.readmodel.events{event_type,outcome}`, `.dead_lettered{topic}` | read-model consumers per service; events that can never be applied or exhausted retries |
| `setu.notification.events`, `.dead_lettered` | the notification consumer |
| `setu.risk.events{event_type,outcome}`, `.dead_lettered{topic}` | the risk consumer: `recalculated`, `unchanged`, `duplicate`, `ignored`, `error` |
| `setu.enrollment.events{event_type,outcome}`, `.dead_lettered{topic}` | the enrollment consumer that completes enrollments on a final grade: `completed`, `corrected`, `unchanged`, `not_enrolled`, `duplicate`, `ignored`, `error` |
| `setu.ai.chat.requests{outcome,model,channel}`, `setu.ai.jobs.submitted{outcome}` | the advisor |

`DashboardConsistencyTests` fails if a dashboard panel names a meter that no code produces, so a renamed meter cannot leave a blank panel.
Tags never carry a user, tenant, student or any id; only bounded vocabularies (outcomes, event types, peers).

## 4. Running it

```
docker compose -f infra/docker/docker-compose.yml up -d tempo loki prometheus grafana     # Grafana on :3001 (admin / admin locally)
OTEL_TRACES_EXPORT_ENABLED=true OTEL_LOGS_EXPORT_ENABLED=true  <start the services>
```

Grafana has Prometheus, Tempo and Loki provisioned (`provisioning/datasources`), the two dashboards, and links between them. Prometheus
scrapes the monolith (`setu-backend`) and each extracted service (`setu-services`, ports 8080-8088). Local only: single-process Tempo and
Loki, filesystem storage, 24-hour retention, no authentication.

| Setting | Default | Meaning |
|---|---|---|
| `TRACING_SAMPLING_PROBABILITY` | 1.0 | share of requests traced (tracing is cheap when nothing is exported; lower it for a busy environment) |
| `OTEL_TRACES_EXPORT_ENABLED` / `OTEL_TRACES_ENDPOINT` | false / `http://localhost:4318/v1/traces` | push spans to Tempo |
| `OTEL_LOGS_EXPORT_ENABLED` / `OTEL_LOGS_ENDPOINT` | false / `http://localhost:3100/otlp/v1/logs` | push logs to Loki |
| profile `file-logs` | off | also write a rolling log file (`logging.file.name` / `logging.file.path`, default `setu.log` in the system temp directory); by default logs go to stdout and OpenTelemetry only (`LoggingSystemTests`) |

## 5. What was verified, and how

* The compose stack was started and exercised for real: an OTLP span posted to Tempo and read back by trace id (also through Grafana's datasource
  proxy); an OTLP log line with a trace id posted to Loki and read back; Grafana loads the three datasources (Loki reports a successful
  connection; Tempo has no health route in this Grafana version, so it was verified by querying through it) and both dashboards; every
  expression of the platform dashboard is valid PromQL against Prometheus.
* `TelemetryExportTests` does the same from the application: a span and a log line produced by the Spring context, exported over OTLP, and found
  in Tempo and Loki by one trace id (skipped when the stack is not running).
* `TraceParentsAndOutboundTests`, `TracingIntegrationTests` (the trace of the writing code is stored on the outbox row and nothing is stored when
  nothing is traced), `OutboxPublisherTests` (the header is sent when present, never invented).

## 6. Not done

* Spans for the *inside* of a service beyond what Spring instruments (database calls are not traced; the repository layer is not wrapped).
* Alert rules. The dashboards show the signals; paging someone on them is an operations decision about thresholds this project has no
  production traffic to base on. The panels that mean "look at this" (dead letters, orphaned references, circuit-open refusals) are red above zero.
* ~~A real run of the whole distributed flow.~~ Done in containers ([containers.md](containers.md)): a trace follows one grade request from the gateway
  through Assessment and through Kafka into Notification, Learning, Attendance and the core; Prometheus scrapes all nine services; 19 of the 25
  dashboard queries return live data (the rest are failure counters that never fired). That run also found that every service tried to push metrics
  to `localhost:4318` every minute, against the rule that metrics are scraped and never pushed: off now.
