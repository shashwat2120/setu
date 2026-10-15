package com.setu.academic.infrastructure.kafka;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Starts the Identity-event listener once the service is up, retrying until the Kafka client can be created. A listener container
 * normally starts with the context, and a broker that is not reachable yet would then stop the whole service from starting; the read
 * model already holds everything up to the last event applied, so the service can run, slightly behind, while the consumer catches up.
 */
@Component
@ConditionalOnProperty(name = "app.academic.consumer.enabled", havingValue = "true")
class AcademicConsumerStarter {

    private static final Logger log = LoggerFactory.getLogger(AcademicConsumerStarter.class);
    private static final long RETRY_DELAY_MS = 10_000;

    private final KafkaListenerEndpointRegistry registry;
    private volatile boolean stopped;
    private volatile Thread worker;

    AcademicConsumerStarter(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startInBackground() {
        Thread t = new Thread(this::startWithRetry, "academic-consumer-starter");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    void startWithRetry() {
        int attempt = 0;
        while (!stopped) {
            attempt++;
            try {
                MessageListenerContainer container = registry.getListenerContainer(AcademicIdentityEventListener.ID);
                if (container != null) {
                    if (!container.isRunning()) {
                        container.start();
                    }
                    log.info("academic identity-event consumer started (attempt {})", attempt);
                    return;
                }
                log.warn("academic consumer: listener container '{}' is not registered", AcademicIdentityEventListener.ID);
            } catch (RuntimeException | LinkageError e) {
                log.warn("academic consumer could not start (attempt {}), retrying in {}s: {}", attempt, RETRY_DELAY_MS / 1000, e.toString());
            }
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @PreDestroy
    void stop() {
        stopped = true;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }
}
