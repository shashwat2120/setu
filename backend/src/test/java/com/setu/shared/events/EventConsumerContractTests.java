package com.setu.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.readmodel.AcademicEventProjector;
import com.setu.shared.readmodel.IdentityEventProjector;
import com.setu.shared.readmodel.ReadModelProjector;
import com.setu.testsupport.EventSchemas;
import com.setu.testsupport.EventSchemas.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The consumer's side of the contract. A consumer that follows an event nobody emits is following a rename that broke it; a consumer that
 * cannot apply an event as it really is serialized is broken in production, not in its own tests. So: every event type the read-model
 * projectors handle must be one a producer really emits, and each one's real record, filled with plausible values and serialized the way the
 * outbox serializes it, must be applied successfully, once, then recognised as a duplicate. Events and envelopes newer than a consumer
 * understands must be refused, never read as the old shape.
 */
@SpringBootTest
class EventConsumerContractTests {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper objectMapper;

    private static Map<String, Schema> registry() {
        return EventSchemas.all().stream().collect(Collectors.toMap(Schema::eventType, Function.identity()));
    }

    private static Set<String> handledByTheReadModels() {
        Set<String> handled = new TreeSet<>(AcademicEventProjector.EVENT_TYPES);
        handled.addAll(IdentityEventProjector.ROLE_EVENTS);
        handled.addAll(IdentityEventProjector.PERSON_EVENTS);
        return handled;
    }

    @Test
    void everyEventTheReadModelsFollowIsOneAProducerReallyEmits() {
        Set<String> missing = new TreeSet<>(handledByTheReadModels());
        missing.removeAll(registry().keySet());
        assertThat(missing).as("followed by a consumer but emitted by no producer").isEmpty();
    }

    @Test
    void eachFollowedEventAppliesAsItIsReallySerializedAndIsThenADuplicate() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String type : handledByTheReadModels()) {
            Schema schema = registry().get(type);
            String json = objectMapper.writeValueAsString(EventSchemas.sample(schema));
            UUID eventId = UUID.randomUUID();
            // people live in Academic's own copy (institution); everything else in the schemas of the services on top of Academic
            ReadModelProjector projector = IdentityEventProjector.PERSON_EVENTS.contains(type)
                ? new IdentityEventProjector(jdbc, objectMapper, "institution", true)
                : IdentityEventProjector.ROLE_EVENTS.contains(type) ? new IdentityEventProjector(jdbc, objectMapper, "learning", false)
                : new AcademicEventProjector(jdbc, objectMapper, "learning");
            try {
                ReadModelProjector.Outcome first = projector.apply(type, eventId.toString(), schema.version(), EventEnvelope.SCHEMA_VERSION, json);
                ReadModelProjector.Outcome second = projector.apply(type, eventId.toString(), schema.version(), EventEnvelope.SCHEMA_VERSION, json);
                if (first != ReadModelProjector.Outcome.APPLIED || second != ReadModelProjector.Outcome.DUPLICATE) {
                    failures.add(type + ": " + first + " then " + second);
                }
            } catch (RuntimeException e) {
                failures.add(type + ": " + e);
            }
        }
        assertThat(failures).as("event types a consumer could not apply from the real payload").isEmpty();
    }

    @Test
    void anEventNewerThanTheConsumerUnderstandsIsRefusedNotReadAsTheOldShape() throws Exception {
        Schema schema = registry().get("StudentEnrolled");
        String json = objectMapper.writeValueAsString(EventSchemas.sample(schema));
        var projector = new AcademicEventProjector(jdbc, objectMapper, "learning");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> projector.apply("StudentEnrolled", id.toString(), 2, EventEnvelope.SCHEMA_VERSION, json))
            .isInstanceOf(UnsupportedEventVersionException.class).hasMessageContaining("version 2");
        assertThatThrownBy(() -> projector.apply("StudentEnrolled", id.toString(), 1, EventEnvelope.SCHEMA_VERSION + 1, json))
            .isInstanceOf(UnsupportedEventVersionException.class).hasMessageContaining("envelope");
        assertThat(jdbc.sql("SELECT count(*) FROM learning.rm_processed_events WHERE event_id = :id").param("id", id).query(Long.class).single())
            .as("a refused event is not recorded as applied, so it can be replayed after the upgrade").isZero();

        // versions that were not carried are read as the version the consumer knows
        assertThat(projector.apply("StudentEnrolled", id.toString(), null, null, json)).isEqualTo(ReadModelProjector.Outcome.APPLIED);
    }

    @Test
    void headerVersionsAreParsedLeniently() {
        assertThat(EventVersions.parse("2")).isEqualTo(2);
        assertThat(EventVersions.parse(" 3 ")).isEqualTo(3);
        assertThat(EventVersions.parse(null)).isNull();
        assertThat(EventVersions.parse("two")).isNull();
        Set<String> seen = new HashSet<>();
        assertThat(seen).isEmpty();
    }
}
