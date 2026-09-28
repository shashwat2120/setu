package com.setu.attendance.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches docs/kafka-event-catalog.md's {@code AttendanceRecorded} row
 * exactly: aggregate {@code attendance_records}, ordering key {@code
 * attendance_session_id} (per-session order matters for the live board),
 * payload {@code attendance_record_id, session_id, enrollment_id, status,
 * marked_at}. Day 9's own instruction explicitly named "wire the
 * transactional outbox for AttendanceRecorded" — this had never actually
 * been done; Day 15 closes that specific, previously-disclosed gap.
 */
public record AttendanceRecordedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID attendanceRecordId,
    UUID sessionId,
    UUID enrollmentId,
    String status,
    Instant markedAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "AttendanceRecorded";
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
        return "attendance_records";
    }
}
