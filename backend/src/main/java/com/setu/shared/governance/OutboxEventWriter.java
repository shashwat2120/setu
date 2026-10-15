package com.setu.shared.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes one row to governance.outbox_events. Callers MUST invoke this
 * within the same transaction as the business change it describes — this
 * class does not manage transactions itself, it participates in whatever
 * transaction is already open (the entire point of the outbox pattern: the
 * business row and the outbox row commit together or not at all).
 * <p>
 * Only the write side. Publishing these rows to Kafka is a separate,
 * not-yet-implemented outbox-publisher process — see package-info.
 */
@Component
public class OutboxEventWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Writes a domain event as the envelope it already describes: its own aggregate, type, version, tenant and correlation ids. */
    public void publish(com.setu.shared.events.DomainEvent event) {
        write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(), event, event.tenantId(),
            event.correlationId(), event.causationId());
    }

    public void write(String aggregateType, UUID aggregateId, String eventType, int eventVersion,
                       Object payload, UUID tenantId, UUID correlationId, UUID causationId) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox payload for " + eventType + " is not serializable", e);
        }
        repository.save(new OutboxEvent(
            UUID.randomUUID(), tenantId, aggregateType, aggregateId, eventType, eventVersion,
            payloadJson, Instant.now(), correlationId, causationId));
    }
}
