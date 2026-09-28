package com.setu.career.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Matches docs/kafka-event-catalog.md's {@code PlacementRecorded} row: aggregate {@code placement_records}, ordering key {@code student_id}, payload {@code placement_id, student_id, employer}. */
public record PlacementRecordedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID placementId,
    UUID studentId,
    String employer
) implements DomainEvent {

    @Override
    public String eventType() {
        return "PlacementRecorded";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "career";
    }

    @Override
    public String aggregateType() {
        return "placement_records";
    }
}
