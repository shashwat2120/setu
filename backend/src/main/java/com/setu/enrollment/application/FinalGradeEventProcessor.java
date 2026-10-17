package com.setu.enrollment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Completes an enrollment when its final grade is published (a decision of the college: publishing a final grade completes the enrollment),
 * driven by the {@code FinalGradePublished} event the Assessment service writes. Assessment and Academic are separate services, so this is an
 * event and not a call; the enrollment is completed shortly after the grade is published, not in the same transaction.
 * <p>
 * <b>Idempotency</b> (canonical spec section 8): the event id is inserted into {@code enrollment.processed_events} in the same transaction as the
 * change. Kafka delivers at least once, so a redelivery finds the id there and does nothing; the insert is the guard, not a prior read, so two
 * consumers racing on one event cannot both act. If the change fails, the transaction rolls back with the guard, so the retry starts clean.
 * <p>
 * <b>Order.</b> Events for one enrollment share a Kafka key and arrive in order. The final grade is applied as the enrollment's state, not as a
 * delta, and the same grade twice is {@link Outcome#UNCHANGED}, so a replay converges. Changes to one enrollment are serialised with a
 * transaction-scoped advisory lock, so a correction racing the first publication cannot both read ENROLLED.
 * <p>
 * Events this module has no use for are acknowledged and reported as {@link Outcome#IGNORED}, not failed.
 */
@Service
public class FinalGradeEventProcessor {

    /** The highest payload version of any event this module reacts to. */
    public static final int SUPPORTED_EVENT_VERSION = 1;

    static final String TRIGGER = "FinalGradePublished";

    public enum Outcome { COMPLETED, CORRECTED, UNCHANGED, NOT_ENROLLED, DUPLICATE, IGNORED }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final EnrollmentService enrollmentService;

    public FinalGradeEventProcessor(JdbcClient jdbc, ObjectMapper objectMapper, EnrollmentService enrollmentService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.enrollmentService = enrollmentService;
    }

    /**
     * @param headerEventId the {@code event_id} Kafka header (the outbox row id); the payload's own {@code eventId} is used if absent
     * @param headerTenantId the {@code tenant_id} Kafka header, if present; must agree with the payload's tenant
     */
    @Transactional
    public Outcome process(String eventType, String headerEventId, String headerTenantId, String payloadJson) {
        if (!TRIGGER.equals(eventType)) {
            return Outcome.IGNORED; // not recorded, so a later release can start handling it
        }
        JsonNode payload = parse(payloadJson);
        UUID eventId = uuid(headerEventId != null ? headerEventId : text(payload, "eventId"), "eventId");
        UUID tenantId = uuid(text(payload, "tenantId"), "tenantId");
        if (headerTenantId != null && !tenantId.equals(uuid(headerTenantId, "tenant_id header"))) {
            throw new PoisonEventException("tenant_id header does not match the payload's tenantId for event " + eventId);
        }
        UUID enrollmentId = uuid(text(payload, "enrollmentId"), "enrollmentId");
        String gradeCode = text(payload, "gradeCode");
        UUID publishedBy = uuid(text(payload, "publishedBy"), "publishedBy");

        // The guard: only the first delivery of this event id gets past this insert.
        int inserted = jdbc.sql("INSERT INTO enrollment.processed_events (event_id, event_type) VALUES (:id, :type) ON CONFLICT (event_id) DO NOTHING")
            .param("id", eventId).param("type", eventType).update();
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }

        // One change per enrollment at a time; released when this transaction ends.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "enrollment-final-grade:" + enrollmentId).query().singleRow();

        return switch (enrollmentService.completeWithFinalGrade(enrollmentId, tenantId, gradeCode, publishedBy)) {
            case COMPLETED -> Outcome.COMPLETED;
            case CORRECTED -> Outcome.CORRECTED;
            case UNCHANGED -> Outcome.UNCHANGED;
            case NOT_ENROLLED -> Outcome.NOT_ENROLLED;
        };
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

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new PoisonEventException("event payload is missing '" + field + "'");
        }
        return n.asText();
    }

    private static UUID uuid(String value, String what) {
        if (value == null) {
            throw new PoisonEventException("event has no " + what);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException(what + " is not a UUID", e);
        }
    }
}
