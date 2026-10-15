package com.setu.academic.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Keeps the Academic service's read model of Identity's data current from Identity's events: who holds which role
 * ({@code UserRoleAssigned}, {@code UserRoleRevoked}) and who people are ({@code PersonRegistered}, {@code PersonUpdated}).
 * <p>
 * <b>Idempotent:</b> the event id is inserted into {@code institution.rm_processed_events} in the same transaction as the change, so a
 * redelivery (Kafka is at least once) finds it there and does nothing. <b>Order-insensitive where it can be:</b> a revocation carries
 * everything needed to record itself, so it upserts, and an assignment never resurrects a grant a revocation already ended. Events for
 * one role assignment or person share a Kafka key, so they arrive in order anyway; this only means a replay from the start of the
 * topic rebuilds the same state. Anything this projector has no use for is ignored and not recorded.
 */
public class IdentityEventProjector {

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public IdentityEventProjector(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** @param headerEventId the {@code event_id} Kafka header (the outbox row id); the payload's own {@code eventId} is used if absent */
    @Transactional
    public Outcome apply(String eventType, String headerEventId, String payloadJson) {
        if (!handles(eventType)) {
            return Outcome.IGNORED;
        }
        JsonNode p = parse(payloadJson);
        UUID eventId = uuid(headerEventId != null ? headerEventId : text(p, "eventId"), "eventId");

        int inserted = jdbc.sql("INSERT INTO institution.rm_processed_events (event_id, event_type) VALUES (:id, :type) ON CONFLICT (event_id) DO NOTHING")
            .param("id", eventId).param("type", eventType).update();
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }

        Instant occurredAt = instant(p, "occurredAt");
        switch (eventType) {
            case "UserRoleAssigned" -> upsertGrant(p, occurredAt, null);
            case "UserRoleRevoked" -> upsertGrant(p, occurredAt, occurredAt);
            default -> upsertPerson(p);
        }
        return Outcome.APPLIED;
    }

    public static boolean handles(String eventType) {
        return switch (eventType) {
            case "UserRoleAssigned", "UserRoleRevoked", "PersonRegistered", "PersonUpdated" -> true;
            default -> false;
        };
    }

    /** Assigned: insert, never overwriting a grant a revocation already ended. Revoked: insert if unseen, otherwise end it. */
    private void upsertGrant(JsonNode p, Instant occurredAt, Instant endsAt) {
        String conflict = endsAt == null
            ? "DO NOTHING"
            : "DO UPDATE SET ends_at = EXCLUDED.ends_at";
        jdbc.sql("""
                INSERT INTO institution.rm_role_grants (user_role_id, user_id, tenant_id, role_code, starts_at, ends_at)
                VALUES (:id, :user, :tenant, :role, :starts, :ends)
                ON CONFLICT (user_role_id) \
                """ + conflict)
            .param("id", uuid(text(p, "userRoleId"), "userRoleId"))
            .param("user", uuid(text(p, "userId"), "userId"))
            .param("tenant", optionalUuid(p, "tenantId"))
            .param("role", required(p, "roleCode"))
            .param("starts", Timestamp.from(occurredAt))
            .param("ends", endsAt == null ? null : Timestamp.from(endsAt))
            .update();
    }

    private void upsertPerson(JsonNode p) {
        jdbc.sql("""
                INSERT INTO institution.rm_persons (person_id, user_id, legal_name, preferred_name, email, phone)
                VALUES (:id, :user, :legal, :preferred, :email, :phone)
                ON CONFLICT (person_id) DO UPDATE SET user_id = EXCLUDED.user_id, legal_name = EXCLUDED.legal_name,
                    preferred_name = EXCLUDED.preferred_name, email = EXCLUDED.email, phone = EXCLUDED.phone
                """)
            .param("id", uuid(text(p, "personId"), "personId"))
            .param("user", optionalUuid(p, "userId"))
            .param("legal", required(p, "legalName"))
            .param("preferred", text(p, "preferredName"))
            .param("email", text(p, "email"))
            .param("phone", text(p, "phone"))
            .update();
    }

    private JsonNode parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new PoisonEventException("event payload is not a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new PoisonEventException("event payload is not valid JSON", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String required(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null || v.isBlank()) {
            throw new PoisonEventException("event payload is missing " + field);
        }
        return v;
    }

    private static UUID uuid(String value, String what) {
        if (value == null) {
            throw new PoisonEventException("event has no " + what);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException(what + " is not a UUID: " + value, e);
        }
    }

    private static UUID optionalUuid(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? null : uuid(v, field);
    }

    private Instant instant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new PoisonEventException("event payload is missing " + field);
        }
        try {
            return objectMapper.convertValue(v, Instant.class);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException(field + " is not a timestamp", e);
        }
    }
}
