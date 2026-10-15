package com.setu.shared.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.institution.domain.CollegeCreatedEvent;
import com.setu.shared.governance.OutboxEventWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the outbox writes and what Kafka carries (docs/contracts/kafka-contracts.md): a complete envelope, the request's correlation id on every
 * event it causes, and an unbroken chain of cause when one event is handled and another written. The row, the Kafka headers and the payload
 * must all say the same thing.
 */
@SpringBootTest
@Transactional
class EventEnvelopeTests {

    @Autowired
    OutboxEventWriter writer;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    EntityManager entityManager;
    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void clearContext() {
        MDC.remove("correlationId");
    }

    private CollegeCreatedEvent event(UUID tenant, UUID correlation, UUID causation) {
        return new CollegeCreatedEvent(UUID.randomUUID(), Instant.now(), tenant, tenant, correlation, causation, "Envelope College", "Asia/Kolkata");
    }

    private Map<String, Object> row(UUID tenant) {
        entityManager.flush();
        return jdbc.sql("SELECT id, producer, schema_version, event_version, correlation_id, causation_id, payload_json::text AS payload "
            + "FROM governance.outbox_events WHERE aggregate_id = :id ORDER BY occurred_at DESC LIMIT 1").param("id", tenant).query().singleRow();
    }

    @Test
    void theRowCarriesTheProducerAndTheEnvelopeVersion() {
        UUID tenant = UUID.randomUUID();
        writer.publish(event(tenant, UUID.randomUUID(), null));

        Map<String, Object> row = row(tenant);

        assertThat(row.get("producer")).isEqualTo("institution");
        assertThat(row.get("schema_version")).isEqualTo(EventEnvelope.SCHEMA_VERSION);
        assertThat(row.get("event_version")).isEqualTo(1);
    }

    @Test
    void anEventWrittenWhileServingARequestCarriesThatRequestsCorrelationIdEverywhere() throws Exception {
        UUID tenant = UUID.randomUUID();
        MDC.put("correlationId", "req-7f3a-demo");
        UUID expected = EventContext.fromRequestId("req-7f3a-demo");

        writer.publish(event(tenant, UUID.randomUUID(), null));

        Map<String, Object> row = row(tenant);
        assertThat(row.get("correlation_id")).isEqualTo(expected);
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        assertThat(payload.get("correlationId").asText()).as("the payload agrees with the row").isEqualTo(expected.toString());
        assertThat(payload.get("causationId").isNull()).isTrue();
        assertThat(EventContext.fromRequestId("req-7f3a-demo")).as("the same request id always maps to the same flow id").isEqualTo(expected);
        assertThat(EventContext.fromRequestId(expected.toString())).as("a request id that is already a UUID is used as it is").isEqualTo(expected);
    }

    @Test
    void withoutARequestOrAConsumerTheEventKeepsItsOwnIds() {
        UUID tenant = UUID.randomUUID();
        UUID own = UUID.randomUUID();

        writer.publish(event(tenant, own, null));

        assertThat(row(tenant).get("correlation_id")).isEqualTo(own);
    }

    @Test
    void anEventWrittenWhileHandlingAnotherContinuesItsFlowAndNamesItAsItsCause() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID flow = UUID.randomUUID();
        UUID handledEvent = UUID.randomUUID();
        MDC.put("correlationId", "some-unrelated-request");

        try (EventContext.Scope ignored = EventContext.open(flow, handledEvent)) {
            writer.publish(event(tenant, UUID.randomUUID(), null));
            assertThat(MDC.get("correlationId")).as("the consumer's log lines carry the flow id").isEqualTo(flow.toString());
        }

        Map<String, Object> row = row(tenant);
        assertThat(row.get("correlation_id")).as("the consumed event's flow wins over the thread's request").isEqualTo(flow);
        assertThat(row.get("causation_id")).isEqualTo(handledEvent);
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        assertThat(payload.get("causationId").asText()).isEqualTo(handledEvent.toString());
        assertThat(MDC.get("correlationId")).as("closing the scope restores what was there").isEqualTo("some-unrelated-request");
        assertThat(EventContext.causationId()).isEmpty();
    }

    @Test
    void aChainOfThreeEventsIsOneFlowWithEachCausedByThePrevious() {
        UUID flow = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        writer.publish(event(t1, flow, null));
        UUID firstEventId = (UUID) row(t1).get("id");
        try (EventContext.Scope ignored = EventContext.open((UUID) row(t1).get("correlation_id"), firstEventId)) {
            writer.publish(event(t2, UUID.randomUUID(), null));
        }
        UUID secondEventId = (UUID) row(t2).get("id");
        try (EventContext.Scope ignored = EventContext.open((UUID) row(t2).get("correlation_id"), secondEventId)) {
            writer.publish(event(t3, UUID.randomUUID(), null));
        }

        assertThat(row(t2).get("correlation_id")).isEqualTo(flow);
        assertThat(row(t3).get("correlation_id")).isEqualTo(flow);
        assertThat(row(t2).get("causation_id")).isEqualTo(firstEventId);
        assertThat(row(t3).get("causation_id")).isEqualTo(secondEventId);
        assertThat(row(t1).get("causation_id")).as("the first event is a root").isNull();
    }

    @Test
    void theKafkaHeadersCarryTheWholeEnvelopeAndOmitWhatDoesNotApply() {
        UUID id = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID causation = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-15T10:15:30Z");

        Map<String, String> full = EventEnvelope.headers(id, "StudentEnrolled", 1, 1, "enrollment", at, tenant, "enrollments", aggregate, correlation, causation);
        assertThat(full).containsEntry("event_id", id.toString()).containsEntry("event_type", "StudentEnrolled").containsEntry("event_version", "1")
            .containsEntry("schema_version", "1").containsEntry("producer", "enrollment").containsEntry("occurred_at", "2026-10-15T10:15:30Z")
            .containsEntry("tenant_id", tenant.toString()).containsEntry("aggregate_type", "enrollments")
            .containsEntry("aggregate_id", aggregate.toString()).containsEntry("correlation_id", correlation.toString())
            .containsEntry("causation_id", causation.toString());

        Map<String, String> platform = EventEnvelope.headers(id, "UserSuspended", 1, 1, "identity", at, null, "users", aggregate, correlation, null);
        assertThat(platform).doesNotContainKeys("tenant_id", "causation_id").hasSize(9);
    }
}
