package com.setu.notification.infrastructure.kafka;

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
 * Starts the notification listener once the application is up, and keeps trying if the Kafka client cannot be created yet.
 * <p>
 * A listener container normally starts with the application context, and if its Kafka client cannot be created the whole application
 * fails to start. A consumer for in-app notifications must not be able to take the college platform down, so the listener is declared
 * with {@code autoStartup = false} and started here, on a background thread, retrying until it works. Everything else (sign-in, grades,
 * attendance) is unaffected while the consumer is not running; events simply wait on the topic, which retains them.
 */
@Component
@ConditionalOnProperty(name = "app.notification.consumer.enabled", havingValue = "true", matchIfMissing = true)
class NotificationConsumerStarter {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumerStarter.class);
    private static final long RETRY_DELAY_MS = 10_000;

    private final KafkaListenerEndpointRegistry registry;
    private volatile boolean stopped;
    private volatile Thread worker;

    NotificationConsumerStarter(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startInBackground() {
        Thread t = new Thread(this::startWithRetry, "notification-consumer-starter");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    void startWithRetry() {
        int attempt = 0;
        while (!stopped) {
            attempt++;
            try {
                MessageListenerContainer container = registry.getListenerContainer(NotificationEventListener.ID);
                if (container != null) {
                    if (!container.isRunning()) {
                        container.start();
                    }
                    log.info("notification consumer started (attempt {})", attempt);
                    return;
                }
                log.warn("notification consumer: listener container '{}' is not registered", NotificationEventListener.ID);
            } catch (RuntimeException | LinkageError e) {
                log.warn("notification consumer could not start (attempt {}), retrying in {}s: {}", attempt, RETRY_DELAY_MS / 1000, e.toString());
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
