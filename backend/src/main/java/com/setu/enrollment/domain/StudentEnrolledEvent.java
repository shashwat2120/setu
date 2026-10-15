package com.setu.enrollment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A student's enrollment in a section became ENROLLED: directly, by capacity override, or by promotion from the waitlist ({@code viaWaitlist}).
 * Written to the outbox in the same transaction as the enrollment change (canonical spec §8), so the event exists if and only if the change
 * committed. Part of the Academic service's contract with the services that keep read models of who is enrolled where (Learning, Attendance,
 * Student Success, Record, Notification): docs/kafka-event-catalog.md.
 */
public record StudentEnrolledEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID enrollmentId,
    UUID sectionId,
    UUID studentId,
    boolean viaWaitlist
) implements DomainEvent {

    @Override
    public String eventType() {
        return "StudentEnrolled";
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
