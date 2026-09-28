package com.setu.shared.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Maps governance.outbox_events (V4) exactly — see package-info for the pattern this serves. */
@Entity
@Table(name = "outbox_events", schema = "governance")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "causation_id")
    private UUID causationId;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, UUID tenantId, String aggregateType, UUID aggregateId, String eventType,
                        int eventVersion, String payloadJson, Instant occurredAt, UUID correlationId,
                        UUID causationId) {
        this.id = id;
        this.tenantId = tenantId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payloadJson = payloadJson;
        this.status = OutboxStatus.NEW;
        this.occurredAt = occurredAt;
        this.correlationId = correlationId;
        this.causationId = causationId;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }

    public String payloadJson() {
        return payloadJson;
    }

    public OutboxStatus status() {
        return status;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public UUID causationId() {
        return causationId;
    }

    /** Day 15: the outbox publisher marks a row published only after a successful, acknowledged Kafka send — see {@code OutboxPublisher}. */
    void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
    }

    public enum OutboxStatus {
        NEW, PUBLISHED, FAILED
    }
}
