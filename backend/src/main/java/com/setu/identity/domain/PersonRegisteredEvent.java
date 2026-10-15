package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A person record was created in Identity (persons are Identity-owned, decision H1). Carries only what other services show or look up by:
 * the name, a contact address and the account the person signs in with; date of birth, gender and address never leave Identity. Persons
 * are platform-level, so the event has no tenant. Services that need to display a person keep a local copy built from this and
 * {@link PersonUpdatedEvent}.
 */
public record PersonRegisteredEvent(
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
        return "PersonRegistered";
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
