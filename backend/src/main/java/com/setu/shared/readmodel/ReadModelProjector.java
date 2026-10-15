package com.setu.shared.readmodel;

/**
 * Turns one event from another service into a change to this service's local read model. A consumer hands every event it reads to the
 * projectors registered in its service; each decides whether the event is its concern.
 */
public interface ReadModelProjector {

    enum Outcome { APPLIED, DUPLICATE, IGNORED }

    /** Whether this projector has any use for events of this type; events nobody handles are acknowledged and not recorded. */
    boolean handles(String eventType);

    /**
     * Applies the event in one transaction together with the record that it has been applied, so a redelivery does nothing.
     *
     * @param headerEventId the {@code event_id} Kafka header (the outbox row id); the payload's own {@code eventId} is used if absent
     * @throws PoisonEventException if the event can never be applied, however often it is retried
     */
    Outcome apply(String eventType, String headerEventId, String payloadJson);
}
