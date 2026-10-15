package com.setu.enrollment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An ENROLLED or WAITLISTED enrollment was dropped; {@code wasEnrolled} says whether it held a seat.
 * Written to the outbox in the same transaction as the enrollment change (canonical spec §8), so the event exists if and only if the change
 * committed. Part of the Academic service's contract with the services that keep read models of who is enrolled where (Learning, Attendance,
 * Student Success, Record, Notification): docs/kafka-event-catalog.md.
 */
public record EnrollmentDroppedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID enrollmentId,
    UUID sectionId,
    UUID studentId,
    Instant droppedAt,
    boolean wasEnrolled
) implements DomainEvent {

    @Override
    public String eventType() {
        return "EnrollmentDropped";
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
        return "enrollments";
    }
}
