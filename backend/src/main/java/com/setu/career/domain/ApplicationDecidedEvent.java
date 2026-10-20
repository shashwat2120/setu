package com.setu.career.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** A placement officer shortlisted, rejected or selected a student's application. Aggregate {@code applications}, ordering key {@code student_id}; {@code reason} is what the officer told the student (null when none was given). */
public record ApplicationDecidedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID applicationId,
    UUID studentId,
    UUID jobId,
    String jobTitle,
    String employer,
    String decision,
    String reason
) implements DomainEvent {

    @Override
    public String eventType() {
        return "ApplicationDecided";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "career";
    }

    @Override
    public String aggregateType() {
        return "applications";
    }
}
