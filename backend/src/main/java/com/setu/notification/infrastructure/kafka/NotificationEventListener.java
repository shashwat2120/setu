package com.setu.notification.infrastructure.kafka;

import com.setu.notification.application.NotificationEventProcessor;
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
 * Consumes the transactional-outbox events and hands each to {@link NotificationEventProcessor}. This class only reads the Kafka
 * envelope (event type, event id and tenant headers) and counts outcomes; all decisions live in the processor. A failure is rethrown,
 * so the error handler in {@link NotificationKafkaConfig} retries it and finally dead-letters it.
 * <p>
 * The container is not started with the application context but by {@link NotificationConsumerStarter}, which retries, so a Kafka client
 * that cannot be created yet never stops the application starting.
 * <p>
 * Switch off with {@code app.notification.consumer.enabled=false} (the test suite does, so that only an explicit test or the running
 * application consumes the shared topics).
 */
@Component
@ConditionalOnProperty(name = "app.notification.consumer.enabled", havingValue = "true", matchIfMissing = true)
class NotificationEventListener {

    /** Container id; {@link NotificationConsumerStarter} looks the listener up by it. */
    static final String ID = "notification-events";

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationEventProcessor processor;
    private final MeterRegistry meterRegistry;

    NotificationEventListener(NotificationEventProcessor processor, MeterRegistry meterRegistry) {
        this.processor = processor;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(id = ID, autoStartup = "false", topics = "#{@notificationTopics.names()}",
        groupId = "${app.notification.consumer.group-id:setu-notification}")
    void onEvent(ConsumerRecord<String, String> record) {
        String eventType = header(record, "event_type");
        if (eventType == null) {
            count("unknown", "error");
            throw new com.setu.notification.application.PoisonEventException(
                "record " + record.topic() + "-" + record.partition() + "@" + record.offset() + " has no event_type header");
        }
        try {
            // An event newer than this consumer understands is refused, not guessed at (dead-lettered, never retried).
            com.setu.shared.events.EventVersions.assertSupported(eventType,
                com.setu.shared.events.EventVersions.parse(header(record, "event_version")),
                com.setu.shared.events.EventVersions.parse(header(record, "schema_version")), NotificationEventProcessor.SUPPORTED_EVENT_VERSION);
            NotificationEventProcessor.Outcome outcome;
            try (com.setu.shared.events.EventContext.Scope ignored = com.setu.shared.events.EventContext.open(
                uuid(header(record, "correlation_id")), uuid(header(record, "event_id")))) {
                outcome = processor.process(eventType, header(record, "event_id"), header(record, "tenant_id"), record.value());
            }
            count(eventType, outcome.name().toLowerCase());
            log.debug("notification consumer: {} on {} -> {}", eventType, record.topic(), outcome);
        } catch (RuntimeException e) {
            count(eventType, "error");
            throw e;
        }
    }

    private void count(String eventType, String outcome) {
        meterRegistry.counter("setu.notification.events", "event_type", eventType, "outcome", outcome).increment();
    }

    private static java.util.UUID uuid(String value) {
        try {
            return value == null ? null : java.util.UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
