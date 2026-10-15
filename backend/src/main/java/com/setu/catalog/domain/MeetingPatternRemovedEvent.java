package com.setu.catalog.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A recurring meeting was removed from a section.
 * Written to the outbox in the same transaction as the change, so the event exists if and only if the change committed. Part of the Academic
 * service's contract with the services that keep read models of Academic's data (docs/contracts/academic-service.md).
 */
public record MeetingPatternRemovedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID meetingPatternId,
    UUID sectionId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "MeetingPatternRemoved";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "catalog";
    }

    @Override
    public String aggregateType() {
        return "meeting_patterns";
    }
}
