package com.setu.risk.infrastructure.kafka;

import com.setu.risk.application.PoisonEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry and dead-letter policy for the risk consumer (docs/kafka-event-catalog.md "Retry/DLQ"): a transient failure, such as a dropped
 * database connection, is retried with backoff for 3 deliveries in all; a poison message goes straight to {@code {topic}.DLQ}. Either way a
 * record that runs out of attempts ends up on the DLQ topic, never silently dropped, and the partition moves on instead of blocking.
 * <p>
 * One failure is treated differently: a {@link com.setu.shared.internalcall.DependencyUnavailableException}, which means the service that owns some of the data the
 * recalculation needs (attendance, assessment, learning) could not be asked. The event is fine and the service will be back, so it is retried with a growing delay (up
 * to {@value #OUTAGE_MAX_INTERVAL_MS} ms) without limit and is never dead-lettered: a brief restart of one service must not cost a student their risk recalculation,
 * and on a first start the core sees the seeded events before those services exist. The partition waits meanwhile, as every event on it needs the same answer.
 * <p>
 * The policy lives on this consumer's <b>own listener container factory</b>, not on a {@link CommonErrorHandler} bean. Spring Boot hands the
 * one {@code CommonErrorHandler} bean to the default factory and hands none at all if there are two, and the notification consumer in the same
 * process already declares one; a second bean would silently switch off retry and dead-lettering for both. A factory of its own cannot do that.
 */
@Configuration
@ConditionalOnProperty(name = "app.risk.consumer.enabled", havingValue = "true", matchIfMissing = true)
class RiskKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(RiskKafkaConfig.class);

    /** Name the listener refers to. */
    static final String CONTAINER_FACTORY = "riskKafkaListenerContainerFactory";

    /** Total deliveries of one record before it is dead-lettered: the first try plus two retries. */
    static final int MAX_ATTEMPTS = 3;

    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> riskKafkaListenerContainerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
        KafkaTemplate<String, String> template, MeterRegistry meterRegistry,
        @Value("${app.risk.consumer.retry-initial-ms:500}") long initialBackoffMs) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler(template, meterRegistry, initialBackoffMs));
        return factory;
    }

    /** The longest wait between two tries while a service the recalculation depends on is not answering. */
    static final long OUTAGE_MAX_INTERVAL_MS = 30_000;

    static CommonErrorHandler errorHandler(KafkaTemplate<String, String> template, MeterRegistry meterRegistry, long initialBackoffMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            meterRegistry.counter("setu.risk.events.dead_lettered", "topic", record.topic()).increment();
            log.error("risk event on {}-{}@{} dead-lettered to {}.DLQ: {}", record.topic(), record.partition(), record.offset(),
                record.topic(), rootMessage(ex));
            // Partition -1: let the producer pick, so the DLQ topic does not need as many partitions as the source topic.
            return new TopicPartition(record.topic() + ".DLQ", -1);
        });
        ExponentialBackOff backOff = new ExponentialBackOff(initialBackoffMs, 2.0);
        backOff.setMaxAttempts(MAX_ATTEMPTS - 1); // retries after the first delivery
        backOff.setMaxInterval(5_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        ExponentialBackOff outageBackOff = new ExponentialBackOff(initialBackoffMs * 4, 2.0);   // no maxAttempts: it goes on for as long as the service is away
        outageBackOff.setMaxInterval(Math.min(OUTAGE_MAX_INTERVAL_MS, initialBackoffMs * 60));   // 30 s with the default 500 ms start
        handler.setBackOffFunction((record, ex) -> {
            if (!isDependencyOutage(ex)) {
                return null;   // the policy above
            }
            meterRegistry.counter("setu.risk.events.waiting_on_dependency", "topic", record.topic()).increment();
            log.warn("risk event on {}-{}@{} is waiting for a service it depends on: {}", record.topic(), record.partition(), record.offset(), rootMessage(ex));
            return outageBackOff;
        });
        // Cannot succeed on a retry: skip the backoff and dead-letter immediately.
        handler.addNotRetryableExceptions(PoisonEventException.class, DataIntegrityViolationException.class,
            com.setu.shared.events.UnsupportedEventVersionException.class);
        return handler;
    }

    /** True when this failure, or anything it was caused by, is a service that owns some of the data not answering. */
    static boolean isDependencyOutage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof com.setu.shared.internalcall.DependencyUnavailableException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
