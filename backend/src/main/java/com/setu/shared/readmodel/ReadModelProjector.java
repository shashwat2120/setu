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
     * @param eventVersion  the {@code event_version} header, null if not carried (read as the version the projector knows)
     * @param schemaVersion the {@code schema_version} header (envelope version), null if not carried
     * @throws PoisonEventException if the event can never be applied, however often it is retried
     * @throws com.setu.shared.events.UnsupportedEventVersionException if the event or envelope is newer than this projector understands
     */
    Outcome apply(String eventType, String headerEventId, Integer eventVersion, Integer schemaVersion, String payloadJson);

    /**
     * For callers that have no envelope (tests, replays of bare payloads): the versions are taken to be the ones the projector knows.
     * Transactional in its own right, because a call through this method reaches the implementation directly, not through its proxy.
     */
    @org.springframework.transaction.annotation.Transactional
    default Outcome apply(String eventType, String headerEventId, String payloadJson) {
        return apply(eventType, headerEventId, null, null, payloadJson);
    }
}
