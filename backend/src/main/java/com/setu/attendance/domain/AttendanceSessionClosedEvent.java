package com.setu.attendance.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An attendance session was closed; {@code absentRecordsGenerated} students with no scan were marked absent by the system.
 * Written to the outbox in the same transaction as the change (canonical spec §8). See docs/kafka-event-catalog.md and the Sprint 6
 * coupling audit (docs/extraction-audit.md section 7) for the services that build their own view from it.
 */
public record AttendanceSessionClosedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID sessionId,
    UUID sectionId,
    Instant closedAt,
    int absentRecordsGenerated
) implements DomainEvent {

    @Override
    public String eventType() {
        return "AttendanceSessionClosed";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "attendance";
    }

    @Override
    public String aggregateType() {
        return "attendance_sessions";
    }
}
