package com.setu.risk.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Matches docs/kafka-event-catalog.md's {@code InterventionCreated} row: aggregate {@code interventions}, ordering key {@code student_id}, payload {@code intervention_id, student_id, owner_user_id}. */
public record InterventionCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID interventionId,
    UUID studentId,
    UUID ownerUserId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "InterventionCreated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "risk";
    }

    @Override
    public String aggregateType() {
        return "interventions";
    }
}
