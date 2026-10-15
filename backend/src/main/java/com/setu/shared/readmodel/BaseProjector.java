package com.setu.shared.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What every projector shares: it writes into one service's schema (so it is given that schema and nothing else), and it records each event
 * id in {@code <schema>.rm_processed_events} in the same transaction as the change, which is what makes a redelivery a no-op.
 */
abstract class BaseProjector implements ReadModelProjector {

    private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,40}");

    protected final JdbcClient jdbc;
    protected final ObjectMapper mapper;
    private final String schema;

    protected BaseProjector(JdbcClient jdbc, ObjectMapper mapper, String schema) {
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("not a valid schema name: " + schema);
        }
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.schema = schema;
    }

    /** {@code <schema>.<table>}; the schema is validated at construction and tables are constants in the subclasses. */
    protected String table(String name) {
        return schema + "." + name;
    }

    /** Applies the event if it is of interest and has not been applied before. */
    @Override
    @Transactional
    public Outcome apply(String eventType, String headerEventId, String payloadJson) {
        if (!handles(eventType)) {
            return Outcome.IGNORED;
        }
        EventPayload payload = EventPayload.parse(payloadJson, mapper);
        String idText = headerEventId != null ? headerEventId : payload.text("eventId");
        if (idText == null) {
            throw new PoisonEventException("event has no eventId");
        }
        UUID eventId = EventPayload.toUuid(idText, "eventId");
        int inserted = jdbc.sql("INSERT INTO " + table("rm_processed_events") + " (event_id, event_type) VALUES (:id, :type) ON CONFLICT (event_id) DO NOTHING")
            .param("id", eventId).param("type", eventType).update();
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }
        project(eventType, payload);
        return Outcome.APPLIED;
    }

    /** Writes the change. Runs inside the transaction that has just claimed the event id. */
    protected abstract void project(String eventType, EventPayload payload);
}
