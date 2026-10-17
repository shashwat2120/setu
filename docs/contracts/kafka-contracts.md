# Kafka contracts (Sprint 6B.1)

What a producer promises and what a consumer may rely on, for every event that crosses a service boundary. The shape of each event is
listed in [../event-schemas.md](../event-schemas.md) (generated, frozen by `EventSchemaFreezeTests`); the catalogue of what each event means
and who is meant to consume it is [../kafka-event-catalog.md](../kafka-event-catalog.md). This document is the rules that apply to all of them.

## 1. The envelope

Every record carries the canonical envelope (canonical spec section 8) as Kafka headers, with the payload JSON as the value:

| Header | Meaning |
|---|---|
| `event_id` | UUID of the event; the idempotency key |
| `event_type` | e.g. `StudentEnrolled` |
| `event_version` | version of this event type's **payload** |
| `schema_version` | version of the **envelope** itself (currently 1) |
| `producer` | the module that owns the event, i.e. the package its class lives in |
| `occurred_at` | ISO-8601 instant the change happened (not when it was published) |
| `tenant_id` | omitted for the platform-scoped identity events |
| `aggregate_type`, `aggregate_id` | the aggregate the event is about; topic `setu.<aggregate_type>`, record key `aggregate_id` |
| `correlation_id` | the flow this event belongs to, see section 4 |
| `causation_id` | the event whose handling produced this one; omitted for a root event |

The same values are columns of `governance.outbox_events` (V82 added `producer` and `schema_version`), so what was written, what was sent
and what the payload says cannot drift: the writer patches `correlationId`/`causationId` into the payload as well.

## 2. Delivery: at-least-once, never claimed as exactly-once

* The event is written to the outbox in the **same transaction** as the change. A rolled-back change publishes nothing; a committed one is
  eventually published.
* The publisher sends with `acks=all` and `enable.idempotence=true`, so a retried send is not duplicated by the broker. It marks the row
  published only after the broker acknowledged it; a crash in between re-sends the event.
* Ordering is per aggregate (the record key is the aggregate id). There is no order across aggregates or topics, and consumers must not assume one.

So **a consumer will see an event more than once and may see related events out of order.** Section 3 is what makes that safe.

## 3. Consumers: idempotent, convergent, refusing what they do not understand

* **Processed-event table per consumer.** Each consumer records `event_id` in its own table (`notification.processed_events`, `risk.processed_events`, `enrollment.processed_events`;
  `<schema>.rm_processed_events` in each service's read model) in the **same transaction** as its effect. A redelivered event is recognised and skipped.
* **Convergence.** Read-model rows are last-write-wins by `occurred_at`, so replays and reordering end in the same state
  (`AcademicReadModelParityTests`, `ReadModelParityTests`).
* **Versions.** A consumer knows the highest payload version it understands per event type and the envelope version. Anything newer is refused
  with `UnsupportedEventVersionException` and **not** recorded as processed, so it can be replayed after the upgrade. Missing version headers
  (events from before they were carried) are read as version 1.
* **Contract test.** `EventConsumerContractTests` takes every event type the read-model projectors follow, builds the real event record, serializes
  it as the outbox does and applies it twice: applied, then duplicate. A consumer following an event nobody emits fails the build.

## 4. Correlation and causation

`EventContext` carries two ids on the thread:

* **Request path.** A request's `X-Correlation-Id` (forwarded by the gateway; `CorrelationIdFilter` generates one when the client sent none) is the
  correlation id of every event the request causes. A request id that is not a UUID maps deterministically to one.
* **Consumer path.** While a consumer handles an event, its context is that event's `correlation_id` and `causation_id = event_id`. Any event
  written meanwhile joins the same flow and names the handled event as its cause; the consumer's log lines carry the flow id.
* The outcome is a chain: root event (no cause) to an event caused by it to the next, all with one correlation id, which is what the tracing
  work in 6C builds on. `EventEnvelopeTests` covers request id, consumer chain and header content.

## 5. Retry and dead letters

* A transient failure is delivered **3 times** in all (backoff 0.5 s, then 1 s), then published, unchanged, to `{topic}.DLQ` with the
  standard dead-letter headers (original topic, partition, offset, exception).
* A failure that cannot succeed on retry goes to the DLQ on the **first** failure: `PoisonEventException` (malformed JSON, missing field, unknown
  reference, tenant mismatch), `DataIntegrityViolationException`, and `UnsupportedEventVersionException`.
* Dead-lettering is counted (`setu.notification.events.dead_lettered`, `setu.risk.events.dead_lettered`, `setu.enrollment.events.dead_lettered`, `setu.readmodel.events.dead_lettered`, tagged by topic) and logged at
  error level. It never blocks the partition and never drops an event silently.
* The risk and enrollment consumers keep this policy on their **own listener container factories**, not on a `CommonErrorHandler` bean: Spring Boot applies the one such
  bean to the default factory and none at all when there are two, and the notification consumer in the same process already declares one
  (`RiskConsumerWiringTests` pins that no second bean exists).
* Tests: `NotificationErrorHandlerTests`, `RiskErrorHandlerTests`, `FinalGradeErrorHandlerTests`, `ReadModelErrorHandlerTests` (policy, no broker), `NotificationKafkaConsumerTests` (real broker where reachable).

## 6. Changing an event

| Change | Allowed how |
|---|---|
| New event | add the record, regenerate (`-Dfreeze.update=true`), review the diff in `event-schemas.txt` and `docs/event-schemas.md` |
| Add a field | the same; consumers ignore fields they do not know; no version bump |
| Remove a field or change its type | **bump `eventVersion()`**; the freeze test fails otherwise. Deploy the consumers that understand the new version before the producer writes it |
| Rename an event type | a new event: emit both for a release, move the consumers, then retire the old one |

An event type has exactly one class, its `producer` is its module, and its topic is `setu.<aggregate>`; the freeze test enforces all three.

## 7. What is not claimed

* Exactly-once delivery: not claimed anywhere. At-least-once plus idempotent consumers gives effectively-once **effects**.
* A global order: only per aggregate.
* A schema registry: the schemas are frozen in the repository and checked at build time. Introducing a registry is a deployment concern for later.
* Only the consumers that exist are covered here: Notification and the read-model projectors. Finance, Career, Risk and AI still run in the
  monolith and are extracted in 6B.3 and later, onto these same rules.
