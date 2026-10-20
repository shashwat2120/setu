package com.setu.catalog.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An academic period was closed: every section in it is closed or cancelled and no student in it is still ENROLLED, so its results are final.
 * Written to the outbox in the same transaction as the close (canonical spec §8). Record is the consumer that will post each student's period
 * result from it (docs/kafka-event-catalog.md).
 */
public record PeriodClosedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID periodId,
    UUID academicYearId,
    String periodCode
) implements DomainEvent {

    @Override
    public String eventType() {
        return "PeriodClosed";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "catalog";
    }

    @Override
    public String aggregateType() {
        return "academic_periods";
    }
}
