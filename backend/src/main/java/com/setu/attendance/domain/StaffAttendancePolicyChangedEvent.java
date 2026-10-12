package com.setu.attendance.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code FacultyAttendancePolicyChanged} (docs/kafka-event-catalog.md): a college saved a new version of its staff-attendance
 * rules. Aggregate {@code faculty_attendance_policy}, ordering key {@code tenant_id}. Times are ISO local-time strings so the
 * payload does not depend on how the shared ObjectMapper renders {@code LocalTime}.
 */
public record StaffAttendancePolicyChangedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    int policyVersion,
    String shiftStart,
    String shiftEnd,
    int graceMinutes,
    int fullDayMinutes,
    int halfDayMinutes,
    UUID actorUserId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "FacultyAttendancePolicyChanged";
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
        return "faculty_attendance_policy";
    }
}
