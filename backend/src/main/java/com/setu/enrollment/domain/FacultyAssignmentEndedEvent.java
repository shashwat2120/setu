package com.setu.enrollment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A faculty assignment was ended. From now on the faculty member is no longer actively assigned to that section.
 * Written to the outbox in the same transaction as the change, so the event exists if and only if the change committed. Part of the Academic
 * service's contract with the services that keep read models of Academic's data (docs/contracts/academic-service.md).
 */
public record FacultyAssignmentEndedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID assignmentId,
    UUID sectionId,
    UUID facultyId,
    java.time.LocalDate endsAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "FacultyAssignmentEnded";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "enrollment";
    }

    @Override
    public String aggregateType() {
        return "faculty_assignments";
    }
}
