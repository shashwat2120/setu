package com.setu.directory.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Matches docs/kafka-event-catalog.md's {@code StudentAdmitted} row exactly:
 * aggregate {@code student_profiles}, ordering key {@code student_id},
 * payload {@code student_id, admission_no, admission_date}. This is the
 * genuine business transition — (none) -&gt; ACTIVE — not a generic CRUD
 * "created" event (Day 6 §3). Written to the outbox only (Day 6 explicitly
 * forbids building the Kafka publisher today); see
 * docs/architecture-notes/event-publication-vs-outbox.md for the
 * commit-before-publish design this participates in.
 */
public record StudentAdmittedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID studentId,
    String admissionNo,
    LocalDate admissionDate
) implements DomainEvent {

    @Override
    public String eventType() {
        return "StudentAdmitted";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "directory";
    }

    @Override
    public String aggregateType() {
        return "student_profiles";
    }
}
