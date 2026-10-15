package com.setu.enrollment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A faculty member was assigned to a section. An assignment is in force while {@code endsAt} is null, which is the rule Academic itself applies; a non-null value here means it was created already ended.
 * Written to the outbox in the same transaction as the change, so the event exists if and only if the change committed. Part of the Academic
 * service's contract with the services that keep read models of Academic's data (docs/contracts/academic-service.md).
 */
public record FacultyAssignedEvent(
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
        return "FacultyAssigned";
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
