package com.setu.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The envelope every domain event carries, matching docs/kafka-event-catalog.md
 * exactly: {@code event_id, event_type, event_version, occurred_at, producer,
 * tenant_id, aggregate_type, aggregate_id, correlation_id, causation_id,
 * schema_version, payload}.
 * <p>
 * {@code tenantId} is nullable &mdash; per docs/kafka-event-catalog.md's
 * envelope note, only the identity-level events (e.g. {@code UserSuspended},
 * {@code SessionRevoked}) are platform-scoped and carry no tenant. Every
 * other event's producer must supply one.
 * <p>
 * Concrete events are module-owned records implementing this interface,
 * living in that module's {@code domain} package &mdash; e.g.
 * {@code com.setu.enrollment.domain.StudentEnrolled}. This interface itself
 * is intentionally the only thing in {@code com.setu.shared.events}: it is
 * the one contract every module's events must satisfy, not a place for
 * event-specific logic.
 */
public interface DomainEvent {

    UUID eventId();

    String eventType();

    /** Per docs/kafka-event-catalog.md: starts at 1, bumped only when this event's payload shape changes. */
    int eventVersion();

    Instant occurredAt();

    /** The Sprint 5 module (or Sprint 6 service) that produced this event, e.g. "enrollment". */
    String producer();

    /** Nullable &mdash; see class-level note. */
    UUID tenantId();

    /** The aggregate root type this event is about, e.g. "enrollments". */
    String aggregateType();

    /** The specific aggregate instance's id. */
    UUID aggregateId();

    UUID correlationId();

    /** The event_id of whatever caused this event, if any (null for a root cause). */
    UUID causationId();
}
