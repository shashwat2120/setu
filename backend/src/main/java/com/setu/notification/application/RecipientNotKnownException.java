package com.setu.notification.application;

/**
 * The event names a student or enrollment this service has not heard of <i>yet</i>. Not poison: the service holds only a copy of Academic's
 * data, fed by its own consumer, and the event that created the student can arrive after the event that mentions it. The listener retries
 * with backoff, and an event that still cannot be resolved lands in the dead-letter topic, from where it can be replayed once the copy has
 * caught up. (In a single-process run the lookup is exact, so this only ever means the id really is unknown.)
 */
public class RecipientNotKnownException extends RuntimeException {

    public RecipientNotKnownException(String message) {
        super(message);
    }
}
