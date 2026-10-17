package com.setu.risk.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.academic.AcademicDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Recalculates a student's risk when the inputs it is computed from change, driven by a Kafka business event instead of a person pressing
 * Recalculate (canonical spec section 16: "grade event -> risk recalculation").
 * <p>
 * <b>What starts it.</b> {@code GradePublished}, which is written once for a new grade and again for a correction (it carries a
 * {@code supersedesId} then). The recalculation always reads the student's <i>current</i> state, not the event's contents, so it cannot be
 * made to compute anything other than what the stored data says: the event is only the reason to look again. Other signals (attendance,
 * submissions, progress) are not triggers yet; adding one is a line in {@link #TRIGGERS} and a test.
 * <p>
 * <b>Idempotency</b> (canonical spec section 8): the event id is inserted into {@code risk.processed_events} in the same transaction as
 * the recalculation. Kafka delivers at least once, so a redelivery finds the id there and does nothing; the insert is the guard, not a prior
 * read, so two consumers racing on one event cannot both recalculate. If the recalculation fails, the transaction rolls back with the guard,
 * so the retry starts clean.
 * <p>
 * <b>Who it is for</b> is derived server-side from the event's own ids: the enrollment named by the event is looked up through the
 * {@link AcademicDirectory} port and gives the student and the tenant; the tenant named by the event must match it, otherwise the event is
 * poison and nothing is calculated. There is no signed-in user; the audit entry for the calculation has no actor and names the event.
 * <p>
 * <b>Concurrency.</b> Two grade events for the same student can arrive on different partitions at once. Recalculations for one student are
 * serialised with a transaction-scoped advisory lock, so the second one sees the first one's row, finds the same snapshot and records
 * nothing, instead of both writing a score and both raising the same alert.
 * <p>
 * Events this module has no trigger for are acknowledged and reported as {@link Outcome#IGNORED}, not failed.
 */
@Service
public class RiskEventProcessor {

    /** The highest payload version of any event this module reacts to. */
    public static final int SUPPORTED_EVENT_VERSION = 1;

    /** The event types that make a recalculation worth doing. */
    static final Set<String> TRIGGERS = Set.of("GradePublished");

    public enum Outcome { RECALCULATED, UNCHANGED, DUPLICATE, IGNORED }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final AcademicDirectory directory;
    private final RiskCalculationService calculationService;

    public RiskEventProcessor(JdbcClient jdbc, ObjectMapper objectMapper, AcademicDirectory directory, RiskCalculationService calculationService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.directory = directory;
        this.calculationService = calculationService;
    }

    /**
     * @param headerEventId the {@code event_id} Kafka header (the outbox row id); the payload's own {@code eventId} is used if absent
     * @param headerTenantId the {@code tenant_id} Kafka header, if present; must agree with the payload's tenant
     */
    @Transactional
    public Outcome process(String eventType, String headerEventId, String headerTenantId, String payloadJson) {
        if (!TRIGGERS.contains(eventType)) {
            return Outcome.IGNORED; // not recorded, so a later release can start handling it
        }
        JsonNode payload = parse(payloadJson);
        UUID eventId = uuid(headerEventId != null ? headerEventId : text(payload, "eventId"), "eventId");
        UUID tenantId = uuid(text(payload, "tenantId"), "tenantId");
        if (headerTenantId != null && !tenantId.equals(uuid(headerTenantId, "tenant_id header"))) {
            throw new PoisonEventException("tenant_id header does not match the payload's tenantId for event " + eventId);
        }
        UUID enrollmentId = uuid(text(payload, "enrollmentId"), "enrollmentId");

        // The guard: only the first delivery of this event id gets past this insert.
        int inserted = jdbc.sql("INSERT INTO risk.processed_events (event_id, event_type) VALUES (:id, :type) ON CONFLICT (event_id) DO NOTHING")
            .param("id", eventId).param("type", eventType).update();
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }

        AcademicDirectory.EnrollmentRef enrollment = directory.enrollment(enrollmentId)
            .orElseThrow(() -> new StudentNotKnownException(eventType + " names an enrollment not known (yet): " + enrollmentId));
        if (!tenantId.equals(enrollment.tenantId())) {
            throw new PoisonEventException(eventType + " tenant does not match enrollment " + enrollmentId);
        }

        // One recalculation per student at a time; released when this transaction ends.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "risk-recalculate:" + enrollment.studentId()).query().singleRow();

        return switch (calculationService.recalculateForEvent(enrollment.studentId(), tenantId, eventId, eventType)) {
            case RECALCULATED -> Outcome.RECALCULATED;
            case UNCHANGED -> Outcome.UNCHANGED;
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
