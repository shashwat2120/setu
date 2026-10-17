package com.setu.risk.infrastructure.kafka;

import com.setu.identity.TestAuthentication;
import com.setu.risk.application.PoisonEventException;
import com.setu.risk.application.RiskEventProcessor;
import com.setu.risk.application.StudentNotKnownException;
import com.setu.shared.events.UnsupportedEventVersionException;
import com.setu.testsupport.Day13TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The risk listener's reading of the Kafka envelope, without a broker: the headers it needs, the version it refuses, the outcomes it counts,
 * and that a recalculation it causes is part of the same flow as the grade that caused it (the grade event's correlation id carried on, the
 * grade event named as the cause of the alert). The decisions themselves are {@code RiskEventProcessorTests}'.
 */
@SpringBootTest
class RiskEventListenerTests {

    private static final String TOPIC = "setu.grades";

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    RiskEventProcessor processor;
    @Autowired
    MeterRegistry meters;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private RiskEventListener listener() {
        return new RiskEventListener(processor, meters);
    }

    private static ConsumerRecord<String, String> record(String payload, String eventType, UUID eventId, UUID tenantId, UUID correlationId, String version) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 5L, "key", payload);
        if (eventType != null) {
            record.headers().add("event_type", eventType.getBytes(StandardCharsets.UTF_8));
        }
        if (eventId != null) {
            record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (tenantId != null) {
            record.headers().add("tenant_id", tenantId.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (correlationId != null) {
            record.headers().add("correlation_id", correlationId.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (version != null) {
            record.headers().add("event_version", version.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private Day13TestFixtures.Scenario scenarioWithGrade(String prefix) {
        var s = fixtures.provisionEnrolledScenario(prefix);
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        // Keep a running backend's own risk consumer (the development database is shared) from handling the fixture's events as well.
        jdbc.sql("INSERT INTO risk.processed_events (event_id, event_type) SELECT id, event_type FROM governance.outbox_events WHERE tenant_id = :t ON CONFLICT (event_id) DO NOTHING")
            .param("t", s.tenant().tenantId()).update();
        TestAuthentication.clear();
        return s;
    }

    private String gradePayload(UUID tenantId) {
        return jdbc.sql("SELECT payload_json::text FROM governance.outbox_events WHERE tenant_id = :t AND event_type = 'GradePublished' ORDER BY occurred_at DESC LIMIT 1")
            .param("t", tenantId).query(String.class).single();
    }

    private double counted(String eventType, String outcome) {
        return meters.counter("setu.risk.events", "event_type", eventType, "outcome", outcome).count();
    }

    @Test
    void theAlertThatFollowsAGradeIsPartOfTheGradesFlow() {
        var s = scenarioWithGrade("RL1");
        UUID tenantId = s.tenant().tenantId();
        UUID gradeEventId = UUID.randomUUID();
        UUID flow = UUID.randomUUID();
        double before = counted("GradePublished", "recalculated");

        listener().onEvent(record(gradePayload(tenantId), "GradePublished", gradeEventId, tenantId, flow, "1"));

        var alert = jdbc.sql("SELECT correlation_id, causation_id FROM governance.outbox_events WHERE event_type = 'RiskScoreChanged' AND aggregate_id = :s")
            .param("s", s.student().studentId()).query().singleRow();
        assertThat(alert.get("correlation_id")).as("the grade event's flow carries on").isEqualTo(flow);
        assertThat(alert.get("causation_id")).as("the grade event is named as the cause").isEqualTo(gradeEventId);
        assertThat(counted("GradePublished", "recalculated")).isEqualTo(before + 1);
    }

    @Test
    void aRecordWithoutAnEventTypeIsPoison() {
        double before = counted("unknown", "error");

        assertThatThrownBy(() -> listener().onEvent(record("{}", null, UUID.randomUUID(), null, null, "1"))).isInstanceOf(PoisonEventException.class);

        assertThat(counted("unknown", "error")).isEqualTo(before + 1);
    }

    @Test
    void anEventNewerThanTheConsumerUnderstandsIsRefusedAndNothingIsCalculated() {
        var s = scenarioWithGrade("RL2");
        UUID tenantId = s.tenant().tenantId();

        assertThatThrownBy(() -> listener().onEvent(record(gradePayload(tenantId), "GradePublished", UUID.randomUUID(), tenantId, null, "2")))
            .isInstanceOf(UnsupportedEventVersionException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM risk.risk_scores WHERE student_id = :s").param("s", s.student().studentId()).query(Integer.class).single()).isZero();
    }

    @Test
    void aFailureIsRethrownSoTheErrorHandlerCanRetryOrDeadLetterIt() {
        var s = scenarioWithGrade("RL3");
        UUID tenantId = s.tenant().tenantId();
        String unknownEnrollment = gradePayload(tenantId).replaceAll("\"enrollmentId\":\\s*\"[0-9a-f-]+\"", "\"enrollmentId\": \"" + UUID.randomUUID() + "\"");
        double before = counted("GradePublished", "error");

        assertThatThrownBy(() -> listener().onEvent(record(unknownEnrollment, "GradePublished", UUID.randomUUID(), tenantId, null, "1")))
            .isInstanceOf(StudentNotKnownException.class);

        assertThat(counted("GradePublished", "error")).isEqualTo(before + 1);
    }
}
