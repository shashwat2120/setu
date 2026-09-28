package com.setu.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps finance.payment_events (V44) — the provider-webhook idempotency
 * guard: {@code provider_event_id} unique (state-machines.md #20's "the
 * transition handler is idempotent against payment_events.provider_event_id
 * ... processing the same webhook twice must not double-apply the state
 * change or the balance adjustment"). Every row here is itself the audit
 * trail for the payment status transition it drove.
 */
@Entity
@Table(name = "payment_events", schema = "finance")
public class PaymentEvent {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaymentEvent() {
    }

    public PaymentEvent(UUID id, UUID paymentId, String providerEventId, String eventType, String payloadJson, Instant receivedAt) {
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new IllegalArgumentException("providerEventId must not be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        this.id = id;
        this.paymentId = paymentId;
        this.providerEventId = providerEventId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.receivedAt = receivedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public String providerEventId() {
        return providerEventId;
    }

    public String eventType() {
        return eventType;
    }

    public String payloadJson() {
        return payloadJson;
    }

    public Instant receivedAt() {
        return receivedAt;
    }
}
