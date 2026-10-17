package com.setu.risk.application;

/**
 * An event this consumer can never handle, however often it is retried: malformed JSON, a missing required field, or an event whose tenant
 * does not match the student it names. The Kafka error handler sends these straight to the {@code {topic}.DLQ} topic without retrying
 * (docs/kafka-event-catalog.md "Retry/DLQ"); a transient failure such as a database connection drop is any other exception and is retried first.
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
