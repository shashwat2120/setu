package com.setu.shared.readmodel.kafka;

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
 * Starts the read-model listener once the service is up, retrying until the Kafka client can be created. A listener container normally
 * starts with the context, and a broker that is not reachable yet would then stop the whole service from starting; the read model already
 * holds everything up to the last event applied, so the service can run, slightly behind, while the consumer catches up.
 */
@Component
@ConditionalOnProperty(name = "app.readmodel.consumer.enabled", havingValue = "true")
class ReadModelConsumerStarter {

    private static final Logger log = LoggerFactory.getLogger(ReadModelConsumerStarter.class);
    private static final long RETRY_DELAY_MS = 10_000;

    private final KafkaListenerEndpointRegistry registry;
    private volatile boolean stopped;
    private volatile Thread worker;

    ReadModelConsumerStarter(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startInBackground() {
        Thread t = new Thread(this::startWithRetry, "readmodel-consumer-starter");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    void startWithRetry() {
        int attempt = 0;
        while (!stopped) {
            attempt++;
            try {
                MessageListenerContainer container = registry.getListenerContainer(ReadModelEventListener.ID);
                if (container != null) {
                    if (!container.isRunning()) {
                        container.start();
                    }
                    log.info("read-model consumer started (attempt {})", attempt);
                    return;
                }
                log.warn("read-model consumer: listener container '{}' is not registered", ReadModelEventListener.ID);
            } catch (RuntimeException | LinkageError e) {
                log.warn("read-model consumer could not start (attempt {}), retrying in {}s: {}", attempt, RETRY_DELAY_MS / 1000, e.toString());
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
