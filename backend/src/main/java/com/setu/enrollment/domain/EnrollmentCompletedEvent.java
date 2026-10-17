package com.setu.enrollment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An enrollment finished: its final grade was published, so it moved from ENROLLED to COMPLETED with that grade as its final status; or, with
 * {@code corrected} true, the final grade of an already completed enrollment was corrected and the final status changed (the enrollment itself
 * stays COMPLETED). Written to the outbox in the same transaction as the change (canonical spec section 8). The facts are the enrollment's state
 * after the change, not a delta, so a consumer that keeps a copy overwrites it, and the last event for an enrollment wins (they share one Kafka
 * key, the enrollment id, so they arrive in order).
 * <p>
 * {@code finalGrade} is the grade code as published (for example {@code B+}); {@code passed} is the college's verdict on it (anything but an F),
 * decided here so that no consumer has to know the grade scale. Consumers: the academic record (degree progress counts the credits of passed
 * courses), analytics. docs/kafka-event-catalog.md.
 */
public record EnrollmentCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID enrollmentId,
    UUID sectionId,
    UUID studentId,
    UUID periodId,
    String finalGrade,
    boolean passed,
    boolean corrected
) implements DomainEvent {

    @Override
    public String eventType() {
        return "EnrollmentCompleted";
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
