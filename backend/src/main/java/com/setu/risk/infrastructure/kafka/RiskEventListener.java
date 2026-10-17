package com.setu.risk.infrastructure.kafka;

import com.setu.risk.application.PoisonEventException;
import com.setu.risk.application.RiskEventProcessor;
import com.setu.shared.events.EventContext;
import com.setu.shared.events.EventVersions;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Consumes the transactional-outbox events that change a student's risk inputs and hands each to {@link RiskEventProcessor}. This class only
 * reads the Kafka envelope (event type, event id, tenant, version headers) and counts outcomes; all decisions live in the processor. A failure
 * is rethrown, so the error handler in {@link RiskKafkaConfig} retries it and finally dead-letters it.
 * <p>
 * The recalculation runs inside an {@link EventContext}, so the {@code RiskScoreChanged} and {@code RecommendationCreated} events it writes
 * carry the grade event's correlation id and name it as their cause: one flow from the grade to the alert.
 * <p>
 * The container is not started with the application context but by {@link RiskConsumerStarter}, which retries, so a Kafka client that cannot
 * be created yet never stops the application starting. Switch off with {@code app.risk.consumer.enabled=false} (the test suite does, so that
 * only an explicit test or the running application consumes the shared topics).
 * <p>
 * Reading starts from the earliest offset for a new consumer group: a grade published before the group existed is still a reason to look
 * again, and looking again at unchanged inputs records nothing.
 */
@Component
@ConditionalOnProperty(name = "app.risk.consumer.enabled", havingValue = "true", matchIfMissing = true)
class RiskEventListener {

    /** Container id; {@link RiskConsumerStarter} looks the listener up by it. */
    static final String ID = "risk-events";

    private static final Logger log = LoggerFactory.getLogger(RiskEventListener.class);

    private final RiskEventProcessor processor;
    private final MeterRegistry meterRegistry;

    RiskEventListener(RiskEventProcessor processor, MeterRegistry meterRegistry) {
        this.processor = processor;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(id = ID, autoStartup = "false", topics = "#{@riskTopics.names()}", containerFactory = RiskKafkaConfig.CONTAINER_FACTORY,
        groupId = "${app.risk.consumer.group-id:setu-risk}", properties = "auto.offset.reset=earliest")
    void onEvent(ConsumerRecord<String, String> record) {
        String eventType = header(record, "event_type");
        if (eventType == null) {
            count("unknown", "error");
            throw new PoisonEventException(
                "record " + record.topic() + "-" + record.partition() + "@" + record.offset() + " has no event_type header");
        }
        try {
            // An event newer than this consumer understands is refused, not guessed at (dead-lettered, never retried).
            EventVersions.assertSupported(eventType, EventVersions.parse(header(record, "event_version")),
                EventVersions.parse(header(record, "schema_version")), RiskEventProcessor.SUPPORTED_EVENT_VERSION);
            RiskEventProcessor.Outcome outcome;
            try (EventContext.Scope ignored = EventContext.open(uuid(header(record, "correlation_id")), uuid(header(record, "event_id")))) {
                outcome = processor.process(eventType, header(record, "event_id"), header(record, "tenant_id"), record.value());
            }
            count(eventType, outcome.name().toLowerCase());
            log.debug("risk consumer: {} on {} -> {}", eventType, record.topic(), outcome);
        } catch (RuntimeException e) {
            count(eventType, "error");
            throw e;
        }
    }

    private void count(String eventType, String outcome) {
        meterRegistry.counter("setu.risk.events", "event_type", eventType, "outcome", outcome).increment();
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
