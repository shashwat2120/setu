package com.setu.enrollment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A section was opened for registration (its status became OPEN).
 * Written to the outbox in the same transaction as the change (canonical spec §8). See docs/kafka-event-catalog.md and the Sprint 6
 * coupling audit (docs/extraction-audit.md section 7) for the services that build their own view from it.
 */
public record CourseSectionOpenedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID sectionId,
    UUID courseId,
    UUID periodId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "CourseSectionOpened";
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
        return "course_sections";
    }
}
