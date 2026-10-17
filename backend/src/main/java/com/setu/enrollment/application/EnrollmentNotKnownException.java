package com.setu.enrollment.application;

/**
 * The event names an enrollment this process has no record of. Not poison: it is retried with backoff, and an event that still cannot be resolved
 * lands in the dead-letter topic, from where it can be replayed. (Where the enrollment table lives the lookup is exact, so this only ever means the
 * id really is unknown.)
 */
public class EnrollmentNotKnownException extends RuntimeException {

    public EnrollmentNotKnownException(String message) {
        super(message);
    }
}
