package com.setu.shared.readmodel.kafka;

import com.setu.shared.events.UnsupportedEventVersionException;
import com.setu.shared.readmodel.PoisonEventException;
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
 * Retry and dead-letter policy for the read-model consumer, the same as the notification consumer's (docs/kafka-event-catalog.md
 * "Retry/DLQ"): three deliveries with backoff for a transient failure, straight to {@code {topic}.DLQ} for a poison message, never silently
 * dropped and never blocking the partition.
 */
@Configuration
@ConditionalOnProperty(name = "app.readmodel.consumer.enabled", havingValue = "true")
class ReadModelKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(ReadModelKafkaConfig.class);

    /** Total deliveries of one record before it is dead-lettered: the first try plus two retries. */
    static final int MAX_ATTEMPTS = 3;

    @Bean
    CommonErrorHandler readModelErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meterRegistry,
                                              @Value("${app.readmodel.consumer.retry-initial-ms:500}") long initialBackoffMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            meterRegistry.counter("setu.readmodel.events.dead_lettered", "topic", record.topic()).increment();
            log.error("read-model event on {}-{}@{} dead-lettered to {}.DLQ: {}", record.topic(), record.partition(), record.offset(),
                record.topic(), rootMessage(ex));
            return new TopicPartition(record.topic() + ".DLQ", -1);
        });
        ExponentialBackOff backOff = new ExponentialBackOff(initialBackoffMs, 2.0);
        backOff.setMaxAttempts(MAX_ATTEMPTS - 1);
        backOff.setMaxInterval(5_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PoisonEventException.class, DataIntegrityViolationException.class, UnsupportedEventVersionException.class);
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
