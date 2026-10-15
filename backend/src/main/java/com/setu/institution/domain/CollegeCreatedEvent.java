package com.setu.institution.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A college (a tenant) was created. The tenant id is the college id. Services keep its time zone to decide what calendar day it is for that college.
 * Written to the outbox in the same transaction as the change, so the event exists if and only if the change committed. Part of the Academic
 * service's contract with the services that keep read models of Academic's data (docs/contracts/academic-service.md).
 */
public record CollegeCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    String name,
    String timezone
) implements DomainEvent {

    @Override
    public String eventType() {
        return "CollegeCreated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "institution";
    }

    @Override
    public String aggregateType() {
        return "colleges";
    }
}
