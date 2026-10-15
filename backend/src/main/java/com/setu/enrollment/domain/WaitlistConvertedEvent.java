package com.setu.enrollment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A waitlist entry was converted into an enrollment because a seat opened. {@code aggregateId} is the SECTION id, the ordering key in
 * docs/kafka-event-catalog.md: promotion order matters per section.
 * Written to the outbox in the same transaction as the enrollment change (canonical spec §8), so the event exists if and only if the change
 * committed. Part of the Academic service's contract with the services that keep read models of who is enrolled where (Learning, Attendance,
 * Student Success, Record, Notification): docs/kafka-event-catalog.md.
 */
public record WaitlistConvertedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID waitlistEntryId,
    UUID enrollmentId,
    UUID sectionId,
    UUID studentId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "WaitlistConverted";
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
        return "waitlist_entries";
    }
}
