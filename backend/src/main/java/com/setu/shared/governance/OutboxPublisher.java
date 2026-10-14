package com.setu.shared.governance;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Day 15 §2: the piece that was missing entirely before this day — every
 * module already writes to {@code governance.outbox_events} in the same
 * transaction as its business change (established since Day 2), but
 * nothing ever read those rows and actually published them to Kafka. This
 * class closes that gap: a poll-based relay, not a Modulith {@code
 * @ApplicationModuleListener} externalization (this codebase's business
 * events are written directly to a plain table, never published via
 * {@code ApplicationEventPublisher} — a deliberately separate mechanism
 * from Spring Modulith's own {@code event_publication} bookkeeping, which
 * this class does not touch at all).
 * <p>
 * <b>Delivery semantics, honestly stated</b>: this is <i>at-least-once</i>,
 * never exactly-once. A row is marked {@code PUBLISHED} only after {@link
 * KafkaTemplate#send} returns a broker-acknowledged result — but if the
 * process crashes between that acknowledgment and this transaction's
 * commit, the row is still {@code NEW} on restart and gets republished,
 * producing a genuine duplicate on the topic. Every consumer MUST therefore
 * be idempotent, deduping by the event id; the notification module's consumer
 * ({@code notification.infrastructure.kafka}) does, with its own processed-event
 * table, per docs/kafka-event-catalog.md's idempotency note.
 * <p>
 * <b>Retry, honestly stated</b>: a row that fails to publish (broker
 * unreachable, timeout) simply stays {@code NEW} and is retried on the
 * next poll — no persisted retry counter, no {@code FAILED} status
 * transition, no DLQ. A bounded retry count and a real DLQ only matter
 * once a real consumer exists to distinguish a merely transient failure
 * (will succeed on retry) from a genuinely poison message (never will) —
 * inventing that machinery now, with no consumer to receive a DLQ
 * message, would be exactly the unnecessary event-driven architecture
 * Day 15's own instruction warns against. Disclosed as a real
 * limitation, not hidden.
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final String topicPrefix;
    private final long sendTimeoutSeconds;
    private final MeterRegistry meterRegistry;

    OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry,
                     @Value("${app.outbox.batch-size:100}") int batchSize,
                     @Value("${app.outbox.topic-prefix:setu.}") String topicPrefix,
                     @Value("${app.outbox.send-timeout-seconds:5}") long sendTimeoutSeconds) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.topicPrefix = topicPrefix;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
        this.meterRegistry = meterRegistry;
        // How many events are waiting to reach Kafka. A backlog that keeps growing means the broker or the publisher is unhealthy.
        Gauge.builder("setu.outbox.pending", repository, r -> {
            try {
                return r.countByStatus(OutboxEvent.OutboxStatus.NEW);
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }).description("Outbox events saved but not yet acknowledged by Kafka").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    @Transactional
    void publishPending() {
        List<OutboxEvent> batch = repository.lockNextBatchForPublishing(batchSize);
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        String topic = topicPrefix + event.aggregateType();
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.aggregateId().toString(), event.payloadJson());
        record.headers().add("event_id", event.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", event.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_version", String.valueOf(event.eventVersion()).getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlation_id", event.correlationId().toString().getBytes(StandardCharsets.UTF_8));
        if (event.tenantId() != null) {
            record.headers().add("tenant_id", event.tenantId().toString().getBytes(StandardCharsets.UTF_8));
        }
        try {
            // Synchronous within this transaction on purpose: the row lock (FOR UPDATE SKIP LOCKED) is held until
            // the send is acknowledged or fails, so a concurrent publisher instance can never see this row as
            // available while it's in flight, and the status update below is only ever applied to a row that
            // genuinely reached the broker.
            kafkaTemplate.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);
            event.markPublished();
            // One counter per event type is also the business activity feed: grades published, attendance recorded, risk changes...
            meterRegistry.counter("setu.outbox.events.published", "event_type", event.eventType()).increment();
        } catch (Exception e) {
            meterRegistry.counter("setu.outbox.events.failed", "event_type", event.eventType()).increment();
            log.warn("outbox event {} ({} on {}) could not be published, leaving NEW for retry",
                event.id(), event.eventType(), topic, e);
        }
    }
}
