package com.setu.finance.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Matches docs/kafka-event-catalog.md's {@code PaymentFailed} row: aggregate {@code payments}, ordering key {@code student_account_id}, payload {@code payment_id, student_id, reason}. */
public record PaymentFailedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID paymentId,
    UUID studentId,
    String reason
) implements DomainEvent {

    @Override
    public String eventType() {
        return "PaymentFailed";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "finance";
    }

    @Override
    public String aggregateType() {
        return "payments";
    }
}
