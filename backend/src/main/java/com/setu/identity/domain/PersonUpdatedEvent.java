package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** A person's name or contact details changed. Same payload as {@link PersonRegisteredEvent}: a consumer replaces its copy. */
public record PersonUpdatedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID personId,
    UUID userId,
    String legalName,
    String preferredName,
    String email,
    String phone
) implements DomainEvent {

    @Override
    public String eventType() {
        return "PersonUpdated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "identity";
    }

    @Override
    public UUID tenantId() {
        return null;
    }

    @Override
    public String aggregateType() {
        return "persons";
    }
}
