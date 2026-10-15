package com.setu.directory.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A faculty profile was created in a college. {@code ownerUserId} is the account that owns it, so services that resolve "who is this signed-in user as faculty" can answer from their own copy.
 * Written to the outbox in the same transaction as the change, so the event exists if and only if the change committed. Part of the Academic
 * service's contract with the services that keep read models of Academic's data (docs/contracts/academic-service.md).
 */
public record FacultyProfileCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID facultyId,
    UUID ownerUserId,
    String status
) implements DomainEvent {

    @Override
    public String eventType() {
        return "FacultyProfileCreated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "directory";
    }

    @Override
    public String aggregateType() {
        return "faculty_profiles";
    }
}
