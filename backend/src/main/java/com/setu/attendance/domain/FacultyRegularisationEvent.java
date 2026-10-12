package com.setu.attendance.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * The four regularisation events in docs/kafka-event-catalog.md ({@code FacultyRegularisationRequested/Approved/Rejected/
 * Withdrawn}). Same aggregate and topic as {@link FacultyAttendanceEvent} ({@code faculty_attendance}, ordering key
 * {@code faculty_id}) so one faculty member's whole attendance story stays in order. {@code reasonOrNote} is the member's
 * reason on Requested and the administrator's note on Approved/Rejected; {@code actorUserId} is whoever performed the step.
 */
public record FacultyRegularisationEvent(
    UUID eventId,
    String eventType,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID regularisationId,
    UUID facultyId,
    String attendanceDate,
    String requestedStatus,
    String status,
    UUID actorUserId,
    String reasonOrNote
) implements DomainEvent {

    public static final String REQUESTED = "FacultyRegularisationRequested";
    public static final String APPROVED = "FacultyRegularisationApproved";
    public static final String REJECTED = "FacultyRegularisationRejected";
    public static final String WITHDRAWN = "FacultyRegularisationWithdrawn";

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
