package com.setu.shared.readmodel.kafka;

import com.setu.shared.readmodel.PoisonEventException;
import com.setu.shared.readmodel.ReadModelProjector;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Feeds the events of the topics this service follows to its {@link ReadModelProjector}s. Reads only the Kafka envelope (event type and id
 * headers) and counts outcomes; every decision is the projectors'. A failure is rethrown so {@link ReadModelKafkaConfig}'s error handler
 * retries it and finally dead-letters it. Started by {@link ReadModelConsumerStarter}, not with the context, so an unreachable broker never
 * stops the service starting; until it runs, the service keeps deciding from its read model as it was.
 * <p>
 * The consumer group starts from the earliest offset, so a fresh service rebuilds its whole read model by replaying the topics. Active only
 * when {@code app.readmodel.consumer.enabled=true}, which each extracted service's entry point sets.
 */
@Component
@ConditionalOnProperty(name = "app.readmodel.consumer.enabled", havingValue = "true")
class ReadModelEventListener {

    /** Container id; {@link ReadModelConsumerStarter} looks the listener up by it. */
    static final String ID = "readmodel-events";

    private static final Logger log = LoggerFactory.getLogger(ReadModelEventListener.class);

    private final List<ReadModelProjector> projectors;
    private final MeterRegistry meterRegistry;

    ReadModelEventListener(List<ReadModelProjector> projectors, MeterRegistry meterRegistry) {
        this.projectors = projectors;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(id = ID, autoStartup = "false", topics = "#{@readModelTopics.names()}",
        groupId = "${app.readmodel.consumer.group-id}", properties = "auto.offset.reset=earliest")
    void onEvent(ConsumerRecord<String, String> record) {
        String eventType = header(record, "event_type");
        if (eventType == null) {
            count("unknown", "error");
            throw new PoisonEventException(
                "record " + record.topic() + "-" + record.partition() + "@" + record.offset() + " has no event_type header");
        }
        try {
            ReadModelProjector.Outcome outcome = ReadModelProjector.Outcome.IGNORED;
            for (ReadModelProjector projector : projectors) {
                if (projector.handles(eventType)) {
                    outcome = projector.apply(eventType, header(record, "event_id"), record.value());
                    break;
                }
            }
            count(eventType, outcome.name().toLowerCase());
            log.debug("read-model consumer: {} on {} -> {}", eventType, record.topic(), outcome);
        } catch (RuntimeException e) {
            count(eventType, "error");
            throw e;
        }
    }

    private void count(String eventType, String outcome) {
        meterRegistry.counter("setu.readmodel.events", "event_type", eventType, "outcome", outcome).increment();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
