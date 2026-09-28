/**
 * Shared, cross-cutting infrastructure — deliberately small.
 * <p>
 * This package exists only for the concerns the ERD itself documents as not
 * owned by any single module (docs/erd/04-finance-career-success-platform.md
 * "governance" section, docs/contradiction-audit.md #6):
 * <ul>
 *   <li>{@code events} &mdash; the Kafka envelope contract every module's
 *       domain events implement, matching docs/kafka-event-catalog.md.</li>
 *   <li>{@code tenant} &mdash; tenant context resolution, since every module's
 *       authorization pipeline needs it identically (TRD &sect;3 "Tenant column",
 *       canonical spec &sect;3 "Tenant isolation").</li>
 *   <li>{@code governance} &mdash; the {@code governance} schema
 *       (audit_logs, outbox_events, idempotency_keys), added Sprint 5B Day 3.
 *       V4's own migration comment already states "there is no single owning
 *       module" for these tables, so they live here rather than inside
 *       whichever module happens to write to them first.</li>
 * </ul>
 * Nothing else belongs here. A module-specific concern goes in that module's
 * own {@code domain}/{@code application}/{@code infrastructure} packages, not
 * here &mdash; this package is not a place to avoid deciding which module owns
 * something.
 * <p>
 * Declared {@code OPEN}: every module's domain/infrastructure code needs to
 * reach into {@code shared}'s nested packages directly (implement
 * {@link com.setu.shared.tenant.TenantContext}, write an
 * {@link com.setu.shared.events.DomainEvent}, use
 * {@link com.setu.shared.governance.OutboxEventWriter}) — this is the one
 * package every module is expected to depend on, by design.
 */
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.setu.shared;
