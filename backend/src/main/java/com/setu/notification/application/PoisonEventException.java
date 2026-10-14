package com.setu.notification.application;

/**
 * An event this consumer can never handle, however often it is retried: malformed JSON, a missing required field, a student that does
 * not exist, or an event whose tenant does not match the person it names. The Kafka error handler sends these straight to the
 * {@code {topic}.DLQ} topic without retrying (docs/kafka-event-catalog.md "Retry/DLQ"); a transient failure such as a database
 * connection drop is any other exception and is retried first.
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
