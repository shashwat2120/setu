package com.setu.assessment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches docs/kafka-event-catalog.md's {@code GradePublished} row exactly:
 * aggregate {@code grades}, ordering key {@code enrollment_id}, payload
 * {@code grade_id, enrollment_id, component_id, version, supersedes_id |
 * null} — consumers distinguish an original publish ({@code supersedesId
 * == null}) from a correction. Written to the outbox only (Day 11
 * explicitly forbids building the Kafka publisher); per the ERD's own
 * note, {@code record} (Academic Record) is this event's real consumer,
 * writing {@code student_academic_records} — but the {@code record} module
 * is itself unimplemented (empty skeleton, correctly out of Day 11 scope),
 * so Day 11's job stops at durably recording this event, never writing to
 * {@code student_academic_records} directly (Day 11 explicit instruction:
 * "do not duplicate Record business logic inside Assessment").
 */
public record GradePublishedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID gradeId,
    UUID enrollmentId,
    UUID componentId,
    int version,
    UUID supersedesId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "GradePublished";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "assessment";
    }

    @Override
    public String aggregateType() {
        return "grades";
    }
}
