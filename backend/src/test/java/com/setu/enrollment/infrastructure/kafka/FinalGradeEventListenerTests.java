package com.setu.enrollment.infrastructure.kafka;

import com.setu.enrollment.application.EnrollmentNotKnownException;
import com.setu.enrollment.application.FinalGradeEventProcessor;
import com.setu.enrollment.application.PoisonEventException;
import com.setu.shared.events.UnsupportedEventVersionException;
import com.setu.testsupport.Day13TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The enrollment listener's reading of the Kafka envelope, without a broker: the headers it needs, the version it refuses, the outcomes it counts,
 * and that the completion it causes is part of the same flow as the final grade that caused it (the grade event's correlation id carried on, the
 * grade event named as the cause). The decisions themselves are {@code FinalGradeEventProcessorTests}'.
 */
@SpringBootTest
class FinalGradeEventListenerTests {

    private static final String TOPIC = "setu.grades";

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    FinalGradeEventProcessor processor;
    @Autowired
    MeterRegistry meters;
    @Autowired
    JdbcClient jdbc;

    private FinalGradeEventListener listener() {
        return new FinalGradeEventListener(processor, meters);
    }

    private static String payload(UUID eventId, UUID tenantId, UUID enrollmentId, UUID publishedBy) {
        return "{\"eventId\":\"" + eventId + "\",\"tenantId\":\"" + tenantId + "\",\"enrollmentId\":\"" + enrollmentId
            + "\",\"gradeCode\":\"B\",\"publishedBy\":\"" + publishedBy + "\"}";
    }

    private static ConsumerRecord<String, String> record(String payload, String eventType, UUID eventId, UUID tenantId, UUID correlationId, String version) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 5L, "key", payload);
        add(record, "event_type", eventType);
        add(record, "event_id", eventId == null ? null : eventId.toString());
        add(record, "tenant_id", tenantId == null ? null : tenantId.toString());
        add(record, "correlation_id", correlationId == null ? null : correlationId.toString());
        add(record, "event_version", version);
        return record;
    }

    private static void add(ConsumerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Day13TestFixtures.Scenario scenario(String prefix) {
        var s = fixtures.provisionEnrolledScenario(prefix);
        // Keep a running backend's own enrollment consumer (the development database is shared) from handling the fixture's events as well.
        jdbc.sql("INSERT INTO enrollment.processed_events (event_id, event_type) SELECT id, event_type FROM governance.outbox_events WHERE tenant_id = :t ON CONFLICT (event_id) DO NOTHING")
            .param("t", s.tenant().tenantId()).update();
        return s;
    }

    private double counted(String eventType, String outcome) {
        return meters.counter("setu.enrollment.events", "event_type", eventType, "outcome", outcome).count();
    }

    @Test
    void theCompletionThatFollowsAFinalGradeIsPartOfTheGradesFlow() {
        var s = scenario("FL1");
        UUID tenantId = s.tenant().tenantId();
        UUID gradeEventId = UUID.randomUUID();
        UUID flow = UUID.randomUUID();
        double before = counted("FinalGradePublished", "completed");

        listener().onEvent(record(payload(gradeEventId, tenantId, s.enrollmentId(), s.faculty().userId()), "FinalGradePublished", gradeEventId, tenantId, flow, "1"));

        var completed = jdbc.sql("SELECT correlation_id, causation_id FROM governance.outbox_events WHERE event_type = 'EnrollmentCompleted' AND aggregate_id = :id")
            .param("id", s.enrollmentId()).query().singleRow();
        assertThat(completed.get("correlation_id")).as("the grade event's flow carries on").isEqualTo(flow);
        assertThat(completed.get("causation_id")).as("the grade event is named as the cause").isEqualTo(gradeEventId);
        assertThat(counted("FinalGradePublished", "completed")).isEqualTo(before + 1);
    }

    @Test
    void aRecordWithoutAnEventTypeIsPoison() {
        double before = counted("unknown", "error");

        assertThatThrownBy(() -> listener().onEvent(record("{}", null, UUID.randomUUID(), null, null, "1"))).isInstanceOf(PoisonEventException.class);

        assertThat(counted("unknown", "error")).isEqualTo(before + 1);
    }

    @Test
    void anEventNewerThanTheConsumerUnderstandsIsRefusedAndNothingIsCompleted() {
        var s = scenario("FL2");
        UUID tenantId = s.tenant().tenantId();
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> listener().onEvent(record(payload(eventId, tenantId, s.enrollmentId(), s.faculty().userId()), "FinalGradePublished", eventId, tenantId, null, "2")))
            .isInstanceOf(UnsupportedEventVersionException.class);

        assertThat(jdbc.sql("SELECT status FROM enrollment.enrollments WHERE id = :id").param("id", s.enrollmentId()).query(String.class).single()).isEqualTo("ENROLLED");
    }

    @Test
    void aFailureIsRethrownSoTheErrorHandlerCanRetryOrDeadLetterIt() {
        var s = scenario("FL3");
        UUID tenantId = s.tenant().tenantId();
        UUID eventId = UUID.randomUUID();
        double before = counted("FinalGradePublished", "error");

        assertThatThrownBy(() -> listener().onEvent(record(payload(eventId, tenantId, UUID.randomUUID(), s.faculty().userId()), "FinalGradePublished", eventId, tenantId, null, "1")))
            .isInstanceOf(EnrollmentNotKnownException.class);

        assertThat(counted("FinalGradePublished", "error")).isEqualTo(before + 1);
    }
}
