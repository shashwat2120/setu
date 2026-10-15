package com.setu.shared.events;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

/**
 * The canonical envelope of every event on Kafka (canonical spec section 8): which headers a record carries and what they mean. The payload is
 * the record's value; everything a consumer needs to route, deduplicate, order, trace and version-check an event is in the headers, so it never
 * has to parse a payload it does not understand to decide to refuse it.
 *
 * <table>
 *   <caption>Headers</caption>
 *   <tr><td>{@code event_id}</td><td>the outbox row id; the consumer's idempotency key</td></tr>
 *   <tr><td>{@code event_type}</td><td>what happened, for example {@code StudentEnrolled}</td></tr>
 *   <tr><td>{@code event_version}</td><td>version of this event type's payload; a breaking change to the payload bumps it</td></tr>
 *   <tr><td>{@code schema_version}</td><td>version of the envelope itself (this set of headers)</td></tr>
 *   <tr><td>{@code producer}</td><td>the module or service that wrote it</td></tr>
 *   <tr><td>{@code occurred_at}</td><td>when it happened (ISO-8601, UTC)</td></tr>
 *   <tr><td>{@code tenant_id}</td><td>the college, absent for the platform-level identity events</td></tr>
 *   <tr><td>{@code aggregate_type}, {@code aggregate_id}</td><td>what it is about; the id is also the record key, so one aggregate's events stay in order</td></tr>
 *   <tr><td>{@code correlation_id}</td><td>the flow it belongs to (see {@link EventContext})</td></tr>
 *   <tr><td>{@code causation_id}</td><td>the event that directly caused it, absent for a root event</td></tr>
 * </table>
 */
public final class EventEnvelope {

    /** Version of the header set above. A consumer that sees a higher one does not guess; see {@link EventVersions}. */
    public static final int SCHEMA_VERSION = 1;

    private EventEnvelope() {
    }

    /** The headers for one event, in a stable order. */
    public static Map<String, String> headers(UUID eventId, String eventType, int eventVersion, int schemaVersion, String producer, Instant occurredAt,
                                              UUID tenantId, String aggregateType, UUID aggregateId, UUID correlationId, UUID causationId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("event_id", eventId.toString());
        headers.put("event_type", eventType);
        headers.put("event_version", Integer.toString(eventVersion));
        headers.put("schema_version", Integer.toString(schemaVersion));
        headers.put("producer", producer);
        headers.put("occurred_at", occurredAt.toString());
        if (tenantId != null) {
            headers.put("tenant_id", tenantId.toString());
        }
        headers.put("aggregate_type", aggregateType);
        headers.put("aggregate_id", aggregateId.toString());
        headers.put("correlation_id", correlationId.toString());
        if (causationId != null) {
            headers.put("causation_id", causationId.toString());
        }
        return headers;
    }

    public static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
