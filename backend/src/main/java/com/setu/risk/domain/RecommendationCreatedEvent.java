package com.setu.risk.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Matches docs/kafka-event-catalog.md's {@code RecommendationCreated} row: aggregate {@code recommendations}, ordering key {@code student_id}, payload {@code recommendation_id, student_id, generated_by}. */
public record RecommendationCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID recommendationId,
    UUID studentId,
    GeneratedBy generatedBy
) implements DomainEvent {

    @Override
    public String eventType() {
        return "RecommendationCreated";
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
        return "recommendations";
    }
}
