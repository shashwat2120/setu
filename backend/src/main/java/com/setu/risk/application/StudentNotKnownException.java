package com.setu.risk.application;

/**
 * The event names an enrollment or student this process has no record of. Not poison: it is retried with backoff, and an event that still
 * cannot be resolved lands in the dead-letter topic, from where it can be replayed. (In the process that holds Academic's tables the lookup is
 * exact, so this only ever means the id really is unknown; it exists so that a future extraction, where the lookup is a read-model copy that
 * may lag the event, behaves correctly without a change here.)
 */
public class StudentNotKnownException extends RuntimeException {

    public StudentNotKnownException(String message) {
        super(message);
    }
}
