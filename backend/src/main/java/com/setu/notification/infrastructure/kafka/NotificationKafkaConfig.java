package com.setu.notification.infrastructure.kafka;

import com.setu.notification.application.PoisonEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry and dead-letter policy for the notification consumer (docs/kafka-event-catalog.md "Retry/DLQ").
 * <p>
 * A transient failure, such as a dropped database connection, is retried with backoff for 3 attempts in all. A poison message, one the
 * consumer can never handle, goes straight to {@code {topic}.DLQ} without retrying. Either way the failed record ends up on the DLQ
 * topic once attempts run out, never silently dropped, and the partition moves on instead of blocking.
 */
@Configuration
@ConditionalOnProperty(name = "app.notification.consumer.enabled", havingValue = "true", matchIfMissing = true)
class NotificationKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationKafkaConfig.class);

    /** Total deliveries of one record before it is dead-lettered: the first try plus two retries. */
    static final int MAX_ATTEMPTS = 3;

    /** Spring Boot applies the one {@link CommonErrorHandler} bean to the auto-configured listener container factory. */
    @Bean
    CommonErrorHandler notificationErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meterRegistry,
                                                 @Value("${app.notification.consumer.retry-initial-ms:500}") long initialBackoffMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            meterRegistry.counter("setu.notification.events.dead_lettered", "topic", record.topic()).increment();
            log.error("notification event on {}-{}@{} dead-lettered to {}.DLQ: {}", record.topic(), record.partition(), record.offset(),
                record.topic(), rootMessage(ex));
            // Partition -1: let the producer pick, so the DLQ topic does not need as many partitions as the source topic.
            return new TopicPartition(record.topic() + ".DLQ", -1);
        });
        ExponentialBackOff backOff = new ExponentialBackOff(initialBackoffMs, 2.0);
        backOff.setMaxAttempts(MAX_ATTEMPTS - 1); // retries after the first delivery
        backOff.setMaxInterval(5_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Cannot succeed on a retry: skip the backoff and dead-letter immediately.
        handler.addNotRetryableExceptions(PoisonEventException.class, DataIntegrityViolationException.class,
            com.setu.shared.events.UnsupportedEventVersionException.class);
        return handler;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
