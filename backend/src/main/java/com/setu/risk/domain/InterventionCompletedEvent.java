package com.setu.risk.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Matches docs/kafka-event-catalog.md's {@code InterventionCompleted} row: aggregate {@code interventions}, ordering key {@code student_id}, payload {@code intervention_id, outcome}. {@code outcome} is the final {@link InterventionAction#action()} text recorded at completion time. */
public record InterventionCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID interventionId,
    String outcome
) implements DomainEvent {

    @Override
    public String eventType() {
        return "InterventionCompleted";
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
