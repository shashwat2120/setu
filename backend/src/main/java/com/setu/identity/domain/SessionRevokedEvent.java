package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches docs/kafka-event-catalog.md's {@code SessionRevoked} row exactly:
 * platform-scoped (no tenant_id), aggregate {@code sessions}, payload
 * {@code session_id, user_id, reason}. No new event type invented — this is
 * the only identity-lifecycle event Day 3's scope (session/refresh flow)
 * actually needs, and it's already catalogued.
 */
public record SessionRevokedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID sessionId,
    UUID userId,
    String reason
) implements DomainEvent {

    @Override
    public String eventType() {
        return "SessionRevoked";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "identity";
    }

    @Override
    public UUID tenantId() {
        return null; // platform-scoped per the catalog's identity-level exception note
    }

    @Override
    public String aggregateType() {
        return "sessions";
    }
}
