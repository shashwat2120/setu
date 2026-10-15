package com.setu.enrollment.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A course section exists, in DRAFT, in a college. Content and assessments can be prepared for a section before it opens for registration,
 * so the services that attach their data to a section need to know it, and which college it belongs to, from the moment it is created, not
 * only from {@code CourseSectionOpened}. Written to the outbox in the same transaction as the section.
 */
public record CourseSectionCreatedEvent(
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
        return "CourseSectionCreated";
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
