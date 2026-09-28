package com.setu.finance.domain;

import com.setu.shared.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Matches docs/kafka-event-catalog.md's {@code PaymentSuccessful} row:
 * aggregate {@code payments}, ordering key {@code student_account_id}.
 * Deviation from the catalog's documented payload, disclosed: it lists
 * {@code charge_id}, but {@code finance.payments} (V44) has no FK to any
 * specific {@code student_charges} row — a payment is posted against a
 * student's whole account, not one charge — so {@code chargeId} cannot be
 * supplied and is omitted rather than invented.
 */
public record PaymentSuccessfulEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID paymentId,
    UUID studentId,
    BigDecimal amount,
    String currency
) implements DomainEvent {

    @Override
    public String eventType() {
        return "PaymentSuccessful";
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
