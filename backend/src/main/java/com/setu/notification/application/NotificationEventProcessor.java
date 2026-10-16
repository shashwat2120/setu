package com.setu.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.websocket.LiveBroadcastRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns one Kafka business event into at most one in-app notification, exactly once per event id.
 * <p>
 * <b>Idempotency</b> (canonical spec §8): the event id is inserted into {@code notification.processed_events} in the same transaction
 * as the notification. Kafka delivers at least once, so a redelivery finds the id already there and does nothing. The insert is the
 * guard, not a prior read, so two consumers racing on the same event cannot both create a notification.
 * <p>
 * <b>Who is told</b> is always derived server-side from the event's own ids, never from anything the message says about a person: a
 * student's user and a grade's student are looked up through the {@link AcademicDirectory} port (Academic's tables in a single process, this
 * service's own copy of them when it runs on its own). The tenant named by the event must match the tenant of the person it resolves to,
 * otherwise the event is poison, not delivered; an id the directory does not know yet is {@link RecipientNotKnownException}, retried.
 * <p>
 * <b>Live delivery</b>: once the notification is stored, its recipient's own topic {@code /topic/notifications/<userId>} is told about it
 * (after commit, so a rolled-back notification is never announced). The topic is subscribable only by that user
 * (NotificationSubscriptionAuthorizer); the notification is also always available over REST, so a missed push loses nothing.
 * <p>
 * Events this module has no notification for are acknowledged and recorded as {@link Outcome#IGNORED}, not failed.
 */
@Service
public class NotificationEventProcessor {

    /** The highest payload version of any event this module notifies on. */
    public static final int SUPPORTED_EVENT_VERSION = 1;

    public enum Outcome { CREATED, DUPLICATE, IGNORED }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final AcademicDirectory directory;
    private final ApplicationEventPublisher events;

    public NotificationEventProcessor(JdbcClient jdbc, ObjectMapper objectMapper, AcademicDirectory directory, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.directory = directory;
        this.events = events;
    }

    /**
     * @param headerEventId the {@code event_id} Kafka header (the outbox row id); the payload's own {@code eventId} is used if absent
     * @param headerTenantId the {@code tenant_id} Kafka header, if present; must agree with the payload's tenant
     */
    @Transactional
    public Outcome process(String eventType, String headerEventId, String headerTenantId, String payloadJson) {
        JsonNode payload = parse(payloadJson);
        UUID eventId = uuid(headerEventId != null ? headerEventId : text(payload, "eventId"), "eventId");
        UUID tenantId = uuid(text(payload, "tenantId"), "tenantId");
        if (headerTenantId != null && !tenantId.equals(uuid(headerTenantId, "tenant_id header"))) {
            throw new PoisonEventException("tenant_id header does not match the payload's tenantId for event " + eventId);
        }

        Optional<Draft> draft = draftFor(eventType, payload, tenantId);
        if (draft.isEmpty()) {
            return Outcome.IGNORED; // not an event this module notifies on; not recorded, so a later release can start handling it
        }

        // The guard: only the first delivery of this event id gets past this insert.
        int inserted = jdbc.sql("INSERT INTO notification.processed_events (event_id, event_type) VALUES (:id, :type) ON CONFLICT (event_id) DO NOTHING")
            .param("id", eventId).param("type", eventType).update();
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }

        Draft d = draft.get();
        ObjectNode content = d.content(eventId, eventType);
        Map<String, Object> stored = jdbc.sql("INSERT INTO notification.notifications (tenant_id, user_id, type, payload_json) VALUES (:tenant, :user, :type, CAST(:payload AS jsonb)) RETURNING id, created_at")
            .param("tenant", tenantId).param("user", d.userId()).param("type", d.type()).param("payload", write(content))
            .query().singleRow();
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("id", stored.get("id").toString());
        live.put("type", d.type());
        live.put("title", d.title());
        live.put("message", d.message());
        live.put("link", d.link());
        live.put("createdAt", stored.get("created_at") instanceof java.sql.Timestamp t ? t.toInstant().toString() : Instant.now().toString());
        events.publishEvent(new LiveBroadcastRequest(LiveDestinations.forUser(d.userId()), live));
        return Outcome.CREATED;
    }

    private Optional<Draft> draftFor(String eventType, JsonNode p, UUID tenantId) {
        return switch (eventType) {
            case "GradePublished" -> {
                UUID enrollmentId = uuid(text(p, "enrollmentId"), "enrollmentId");
                AcademicDirectory.EnrollmentRef enrollment = directory.enrollment(enrollmentId)
                    .orElseThrow(() -> new RecipientNotKnownException("GradePublished names an enrollment not known (yet): " + enrollmentId));
                if (!tenantId.equals(enrollment.tenantId())) {
                    throw new PoisonEventException("GradePublished tenant does not match enrollment " + enrollmentId);
                }
                yield Optional.of(student(enrollment.studentId(), tenantId, "GRADE_PUBLISHED", "A new grade has been published",
                    "A grade for one of your courses is now available.", "/app/student/grades"));
            }
            case "PaymentSuccessful" -> Optional.of(student(studentId(p), tenantId, "PAYMENT_RECEIVED", "Payment received",
                "Your payment of " + amount(p) + " was received.", "/app/student/finance"));
            case "PaymentFailed" -> Optional.of(student(studentId(p), tenantId, "PAYMENT_FAILED", "Payment failed",
                "A payment attempt did not go through. Nothing was charged; you can try again.", "/app/student/finance"));
            case "PaymentRefunded" -> Optional.of(student(studentId(p), tenantId, "PAYMENT_REFUNDED", "Payment refunded",
                "A refund of " + amount(p) + " was issued for one of your payments.", "/app/student/finance"));
            case "PlacementRecorded" -> Optional.of(student(studentId(p), tenantId, "PLACEMENT_RECORDED", "Placement recorded",
                "Your placement with " + text(p, "employer") + " has been recorded.", "/app/student/career"));
            case "RecommendationCreated" -> Optional.of(student(studentId(p), tenantId, "RECOMMENDATION_AVAILABLE", "New study recommendation",
                "There is a new recommendation to help you stay on track.", "/app/student/success"));
            case "StudentWaitlisted" -> Optional.of(student(studentId(p), tenantId, "WAITLISTED", "You are on a waitlist",
                "The section you requested is full. You are number " + p.path("position").asInt() + " on its waitlist.", "/app/student/courses"));
            case "WaitlistConverted" -> Optional.of(student(studentId(p), tenantId, "WAITLIST_PROMOTED", "A seat opened up",
                "A seat became available and you have been enrolled in the section you were waiting for.", "/app/student/courses"));
            case "StudentAdmitted" -> Optional.of(student(uuid(text(p, "studentId"), "studentId"), tenantId, "ADMISSION_CONFIRMED",
                "Welcome to the college", "Your admission has been recorded. Your courses and schedule will appear here.", "/app/student"));
            case "InterventionCreated" -> {
                // Addressed to the advisor who owns the intervention, not to the student it is about.
                UUID owner = uuid(text(p, "ownerUserId"), "ownerUserId");
                yield Optional.of(new Draft(owner, "INTERVENTION_ASSIGNED", "A student needs follow-up",
                    "A student-success intervention has been assigned to you.", null));
            }
            default -> Optional.empty();
        };
    }

    private Draft student(UUID studentId, UUID tenantId, String type, String title, String message, String link) {
        AcademicDirectory.TenantOwner owner = directory.studentOwner(studentId)
            .orElseThrow(() -> new RecipientNotKnownException("event names a student not known (yet): " + studentId));
        if (!tenantId.equals(owner.tenantId())) {
            throw new PoisonEventException("event tenant does not match the tenant of student " + studentId);
        }
        if (owner.ownerUserId() == null) {
            throw new RecipientNotKnownException("student " + studentId + " has no account to notify (yet)");
        }
        return new Draft(owner.ownerUserId(), type, title, message, link);
    }

    private static UUID studentId(JsonNode p) {
        return uuid(text(p, "studentId"), "studentId");
    }

    private static String amount(JsonNode p) {
        String currency = p.hasNonNull("currency") ? p.get("currency").asText() : "INR";
        JsonNode a = p.get("amount");
        if (a == null || !a.isNumber()) {
            throw new PoisonEventException("payment event has no numeric amount");
        }
        return currency + " " + a.decimalValue().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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

    private String write(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
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

    private record Draft(UUID userId, String type, String title, String message, String link) {

        /** The stored body: a short human message plus where to go, and the event it came from. No personal data beyond what the recipient already owns. */
        ObjectNode content(UUID eventId, String eventType) {
            ObjectNode node = new ObjectMapper().createObjectNode();
            node.put("title", title).put("message", message).put("link", link)
                .put("eventId", eventId.toString()).put("eventType", eventType);
            return node;
        }
    }
}
