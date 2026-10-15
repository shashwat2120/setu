package com.setu.academic.application;

/**
 * An Identity event the projector can never apply, however often it is retried: malformed JSON or a missing required field. The Kafka
 * error handler sends these straight to the {@code {topic}.DLQ} topic without retrying; a transient failure such as a dropped database
 * connection is any other exception and is retried first.
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
