package com.setu.academic.infrastructure.kafka;

import com.setu.academic.application.IdentityEventProjector;
import com.setu.academic.application.PoisonEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Feeds Identity's role and person events to {@link IdentityEventProjector}. Reads only the Kafka envelope (event type and id headers)
 * and counts outcomes; every decision is the projector's. A failure is rethrown so {@link AcademicKafkaConfig}'s error handler retries
 * it and finally dead-letters it. Started by {@link AcademicConsumerStarter}, not with the context, so an unreachable broker never
 * stops the service starting; until it runs, authorization keeps using the read model as it was.
 * <p>
 * The consumer group starts from the earliest offset, so a fresh Academic service rebuilds its read model by replaying the topics.
 */
@Component
@ConditionalOnProperty(name = "app.academic.consumer.enabled", havingValue = "true")
class AcademicIdentityEventListener {

    /** Container id; {@link AcademicConsumerStarter} looks the listener up by it. */
    static final String ID = "academic-identity-events";

    private static final Logger log = LoggerFactory.getLogger(AcademicIdentityEventListener.class);

    private final IdentityEventProjector projector;
    private final MeterRegistry meterRegistry;

    AcademicIdentityEventListener(IdentityEventProjector projector, MeterRegistry meterRegistry) {
        this.projector = projector;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(id = ID, autoStartup = "false", topics = "#{@academicIdentityTopics.names()}",
        groupId = "${app.academic.consumer.group-id:setu-academic}",
        properties = "auto.offset.reset=earliest")
    void onEvent(ConsumerRecord<String, String> record) {
        String eventType = header(record, "event_type");
        if (eventType == null) {
            count("unknown", "error");
            throw new PoisonEventException(
                "record " + record.topic() + "-" + record.partition() + "@" + record.offset() + " has no event_type header");
        }
        try {
            IdentityEventProjector.Outcome outcome = projector.apply(eventType, header(record, "event_id"), record.value());
            count(eventType, outcome.name().toLowerCase());
            log.debug("academic consumer: {} on {} -> {}", eventType, record.topic(), outcome);
        } catch (RuntimeException e) {
            count(eventType, "error");
            throw e;
        }
    }

    private void count(String eventType, String outcome) {
        meterRegistry.counter("setu.academic.identity_events", "event_type", eventType, "outcome", outcome).increment();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
