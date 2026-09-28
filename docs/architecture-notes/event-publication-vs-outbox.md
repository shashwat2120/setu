# Spring Modulith `event_publication` vs. the business outbox vs. Kafka

Sprint 5B Day 3 required this distinction to be confirmed and documented
explicitly, because the two tables sit side by side in the schema
(`public.event_publication`, `governance.outbox_events`) and are easy to
mistake for the same mechanism. **They are not.** Only one of the two is
the canonical business event path, and this document exists so that
never becomes ambiguous.

## The canonical business event path (unchanged, still the only one)

```
business transaction
+ INSERT into governance.outbox_events (same transaction, same commit)
→ COMMIT
→ outbox publisher (tails status='NEW' rows — not yet implemented, future work)
→ Kafka
```

This is what every module uses for its own domain events —
`SessionRevoked`, `PasskeyRevoked`, `GradePublished`, `StudentEnrolled`,
and every other row in `docs/kafka-event-catalog.md`. It is written to
directly from application-layer code (`OutboxEventWriter`, in
`com.setu.shared.governance`) inside the same `@Transactional` method as
the business change it describes. **This is the only business event path
in this codebase. There is exactly one.**

## Spring Modulith's `event_publication` table: unrelated infrastructure

`event_publication` (in `public` schema, added by `V50`) is **not** part
of the above path. It is Spring Modulith's own internal bookkeeping table
for its `spring-modulith-starter-jpa` module — a mechanism for tracking
completion/retry of **Spring `ApplicationEvent`s dispatched to
`@ApplicationModuleListener`-annotated methods inside the same JVM.**

This project does not use that mechanism for business events, and Day 3's
code does not call `ApplicationEventPublisher.publishEvent(...)` anywhere.
The table exists only because `spring-modulith-starter-jpa` is a project
dependency (for `ApplicationModules.of(...).verify()` and the module
documentation generator used by `ModularityTests`), and that starter's
JPA-backed event registry auto-configures its schema requirement
regardless of whether the application ever calls
`publishEvent()` — which is exactly how this table's absence was first
discovered as a real gap during Day 2 verification (Hibernate's schema
validation failed at startup even though no code referenced it), not
guessed at.

**If `event_publication` ever starts filling with rows**, that means some
code somewhere started calling `ApplicationEventPublisher.publishEvent()`
with an event type Modulith's listener-discovery picked up — which would
be a **second, competing event-publication path** alongside the outbox,
and exactly the kind of silent architectural drift this note exists to
prevent. If that's ever genuinely wanted (e.g. for pure in-process,
non-Kafka-bound module-to-module notification), it must be an explicit,
disclosed decision — not an accidental side effect of using
`@ApplicationModuleListener` for convenience.

## Why not just use Modulith's own event externalization instead of hand-rolling an outbox?

Spring Modulith does offer an externalization extension
(`spring-modulith-events-kafka`) that could, in principle, replace a
hand-rolled outbox with `event_publication` itself as the durability
mechanism. This project deliberately does not use it, for reasons already
locked before Sprint 5A:

1. The outbox schema (`governance.outbox_events`) and its envelope shape
   (`event_id`, `event_type`, `event_version`, `tenant_id`,
   `aggregate_type`/`aggregate_id`, `correlation_id`/`causation_id`) were
   designed independently to match `docs/kafka-event-catalog.md` exactly,
   including fields (like `causation_id`) that predate any Modulith
   integration decision.
2. `outbox_events` is queryable/auditable general SQL, owned by no single
   module (`V4`'s own comment: "there is no single owning module") — a
   deliberate cross-cutting design already established before Modulith's
   event-publication registry was even discovered as a dependency
   requirement.
3. Coupling the business event contract to a specific Spring Modulith
   extension's schema would make a future Sprint 6 service extraction
   (splitting modules into separately-deployed services) harder, not
   easier — the outbox table's shape has no framework dependency at all.

## Summary table

| | `event_publication` | `governance.outbox_events` |
|---|---|---|
| Owner | Spring Modulith (`spring-modulith-starter-jpa`) | This project (no single module — see V4) |
| Schema | `public` | `governance` |
| Tracks | In-JVM `ApplicationEvent` → `@ApplicationModuleListener` dispatch completion | Business domain events awaiting Kafka publication |
| Written by | Modulith's own event-publication registry, only if `publishEvent()` is called | `OutboxEventWriter`, explicitly, in the same transaction as the business change |
| Used by this project for business events? | **No** | **Yes — the only path** |
| Currently populated? | Empty (no code calls `publishEvent()`) | Populated by `SessionRevoked`/`PasskeyRevoked` as of Day 3 |
