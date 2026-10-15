package com.setu.directory.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A faculty profile moved to a new status (for example ACTIVE to INACTIVE). Anything but ACTIVE blocks staff self-service in the services that keep a copy.
 * Written to the outbox in the same transaction as the change, so the event exists if and only if the change committed. Part of the Academic
 * service's contract with the services that keep read models of Academic's data (docs/contracts/academic-service.md).
 */
public record FacultyStatusChangedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID facultyId,
    String status
) implements DomainEvent {

    @Override
    public String eventType() {
        return "FacultyStatusChanged";
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
