package com.setu.attendance.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * One envelope for the four staff-attendance events in docs/kafka-event-catalog.md: {@code FacultyCheckedIn},
 * {@code FacultyCheckedOut}, {@code FacultyAttendanceMarked} (an administrator created the day) and
 * {@code FacultyAttendanceCorrected} (an administrator changed an existing day). Aggregate {@code faculty_attendance},
 * ordering key {@code faculty_id} (a faculty member's events stay in order). Dates are ISO strings on purpose so the
 * payload does not depend on how the shared ObjectMapper renders {@code LocalDate}.
 * <p>
 * Fields that do not apply to a given event type are null: {@code previousStatus}/{@code actorUserId}/{@code reason} are
 * only set for the two administrator events, {@code checkInAt}/{@code checkOutAt} only when the row carries them.
 */
public record FacultyAttendanceEvent(
    UUID eventId,
    String eventType,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID facultyAttendanceId,
    UUID facultyId,
    String attendanceDate,
    String status,
    String previousStatus,
    Instant checkInAt,
    Instant checkOutAt,
    UUID actorUserId,
    String reason,
    Integer lateMinutes,
    Integer workedMinutes,
    Integer policyVersion
) implements DomainEvent {

    public static final String CHECKED_IN = "FacultyCheckedIn";
    public static final String CHECKED_OUT = "FacultyCheckedOut";
    public static final String MARKED = "FacultyAttendanceMarked";
    public static final String CORRECTED = "FacultyAttendanceCorrected";

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
        return "faculty_attendance";
    }
}
