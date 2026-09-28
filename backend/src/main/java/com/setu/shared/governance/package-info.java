/**
 * The {@code governance} schema (audit_logs, outbox_events, idempotency_keys
 * — V4). See the parent package's javadoc for why this is shared rather than
 * module-owned.
 * <p>
 * {@link com.setu.shared.governance.OutboxEventWriter} is the write side of
 * the transactional outbox pattern only — it inserts a row into
 * {@code governance.outbox_events} in the same transaction as the business
 * change it describes. It does not publish to Kafka; a separate outbox
 * publisher process that tails {@code status = 'NEW'} rows is future work,
 * not part of Sprint 5B Day 3. See {@code docs/architecture-notes/
 * event-publication-vs-outbox.md} for why this is a deliberately different
 * mechanism from Spring Modulith's own {@code event_publication} table.
 * <p>
 * {@link com.setu.shared.governance.IdempotencyGuard} is the write side of
 * client-request-level idempotency (Day 12, the first real consumer of
 * {@code governance.idempotency_keys}) — see its own javadoc for the full
 * claim/replay/conflict design.
 */
package com.setu.shared.governance;
