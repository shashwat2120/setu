# Setu backend — architecture (Sprint 5A Day 1)

Spring Boot 4.1.1 + Java 25 LTS + Spring Modulith 2.1.1, a strict modular
monolith per [docs/FINAL_CANONICAL_PROJECT_SPEC.md](../docs/FINAL_CANONICAL_PROJECT_SPEC.md)
§2 and [docs/TRD.md](../docs/TRD.md) §2.

## Module structure

16 modules, one top-level package each under `com.setu`, matching
[docs/table-ownership-map.md](../docs/table-ownership-map.md) exactly:
`identity, directory, guardianaccess, institution, catalog, enrollment,
attendance, learning, assessment, record, finance, career, risk, aiexplain,
notification, analytics`. (Java package names can't carry hyphens — `guardian-access`
→ `guardianaccess`, `ai-explain` → `aiexplain`; the Postgres schema names
keep the underscored form, see [docs/schema-per-module-map.md](../docs/schema-per-module-map.md).)

Each module has three sub-packages, enforced as a convention every future
class must follow:

- **`domain`** — entities, value objects, domain events. Never referenced
  directly by another module.
- **`application`** — application services (use cases). **The only layer
  another module may call into**, and only through an interface here.
- **`infrastructure`** — JPA repository implementations and other technical
  adapters for this module's own domain only.

Every module and layer has a `package-info.java` stating its purpose and
(for module roots) a Spring Modulith `@ApplicationModule` annotation.

## Enforcement, not just convention

`src/test/java/com/setu/ModularityTests.java` runs
`ApplicationModules.of(BackendApplication.class).verify()` — a module
boundary violation (code in one module reaching directly into another
module's `domain`/`infrastructure` package instead of its `application`
layer) **fails the build**, per AGENTS.md's standing rule #3. This test
passes today with all 16 modules correctly detected and zero violations —
verified by actually running it (`./mvnw test -Dtest=ModularityTests`), not
assumed. The same test also writes a PlantUML/AsciiDoc snapshot of the
module graph to `target/spring-modulith-docs/` on every run.

## Shared infrastructure — `com.setu.shared`, deliberately small

Two things live outside any single module, because the ERD itself documents
them as not module-owned:

- **`shared.events.DomainEvent`** — the Kafka envelope contract
  (`event_id, event_type, event_version, occurred_at, producer, tenant_id,
  aggregate_type, aggregate_id, correlation_id, causation_id`) every module's
  concrete domain events implement, matching
  [docs/kafka-event-catalog.md](../docs/kafka-event-catalog.md) exactly.
  Concrete events (e.g. `StudentEnrolled`) are module-owned records in that
  module's own `domain` package, implementing this interface — the interface
  itself is the only shared piece.
- **`shared.tenant.TenantContext`** — tenant resolution from the security
  context, since every module's authorization pipeline needs it identically
  (canonical spec §3). The real Spring Security-backed implementation lands
  in Sprint 5B; this interface is the seam every module is written against
  from Day 1.

Nothing else is shared. A module-specific concern belongs in that module's
own packages, never here.

## Transaction boundaries

Per [docs/transaction-concurrency-matrix.md](../docs/transaction-concurrency-matrix.md):
each module's `application` services own their `@Transactional` boundaries.
A transaction never spans two modules' tables directly — cross-module
effects happen through the outbox/Kafka flow (commit → outbox insert →
publish), never a synchronous call into another module's repository inside
the same transaction. This is why the module-boundary test above matters: it
makes that rule mechanically enforced, not just documented.

## What Day 1 deliberately does not include

No entities, no repositories, no controllers, no business logic — per the
Sprint 5A Day 1 scope. Day 2 adds the database schema; Sprint 5B onward adds
entities module by module, each already slotted into the layer structure
above.
