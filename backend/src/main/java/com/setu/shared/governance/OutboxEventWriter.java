package com.setu.shared.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setu.shared.events.DomainEvent;
import com.setu.shared.events.EventContext;
import com.setu.shared.events.EventEnvelope;
import com.setu.shared.observability.TraceParents;
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
 * The outbox publisher ({@code OutboxPublisher}) relays these rows to Kafka.
 * <p>
 * <b>The envelope is completed here</b> (docs/contracts/kafka-contracts.md), so no module has to remember it:
 * <ul>
 *   <li>{@code producer} is the event's own ({@link DomainEvent#producer()}); {@code schema_version} is the envelope version;</li>
 *   <li>{@code correlation_id} is the flow the event belongs to: the consumer's flow when written while handling another event, otherwise the
 *       current HTTP request's {@code X-Correlation-Id}, otherwise whatever the event carried itself ({@link EventContext});</li>
 *   <li>{@code causation_id} is the event being handled, when there is one.</li>
 * </ul>
 * The payload's own {@code correlationId} and {@code causationId} are rewritten to the same values, so the row, the Kafka headers and the
 * payload never disagree.
 * <p>
 * The current trace, if there is one, is stored with the row ({@code trace_parent}) and sent as the standard {@code traceparent} header, so the
 * spans of whoever consumes the event continue the trace of the request that caused it, even though the event is published later by another
 * thread (docs/contracts/observability.md).
 */
@Component
public class OutboxEventWriter {

    private static final String UNKNOWN_PRODUCER = "unknown";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final TraceParents traceParents;

    public OutboxEventWriter(OutboxEventRepository repository, ObjectMapper objectMapper, TraceParents traceParents) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.traceParents = traceParents;
    }

    /** Writes a domain event as the envelope it already describes: its own aggregate, type, version, tenant and producer. */
    public void publish(DomainEvent event) {
        write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(), event, event.tenantId(),
            event.correlationId(), event.causationId());
    }

    public void write(String aggregateType, UUID aggregateId, String eventType, int eventVersion,
                       Object payload, UUID tenantId, UUID correlationId, UUID causationId) {
        UUID effectiveCorrelation = EventContext.correlationId().orElse(correlationId != null ? correlationId : UUID.randomUUID());
        UUID effectiveCausation = EventContext.causationId().orElse(causationId);
        String producer = payload instanceof DomainEvent event ? event.producer() : UNKNOWN_PRODUCER;
        String payloadJson;
        try {
            JsonNode tree = objectMapper.valueToTree(payload);
            if (tree instanceof ObjectNode object) {
                if (object.has("correlationId")) {
                    object.put("correlationId", effectiveCorrelation.toString());
                }
                if (object.has("causationId")) {
                    if (effectiveCausation == null) {
                        object.putNull("causationId");
                    } else {
                        object.put("causationId", effectiveCausation.toString());
                    }
                }
            }
            payloadJson = objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Outbox payload for " + eventType + " is not serializable", e);
        }
        repository.save(new OutboxEvent(
            UUID.randomUUID(), tenantId, aggregateType, aggregateId, eventType, eventVersion,
            payloadJson, Instant.now(), effectiveCorrelation, effectiveCausation, producer, EventEnvelope.SCHEMA_VERSION,
            traceParents.current().orElse(null)));
    }
}
