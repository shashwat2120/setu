package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches docs/kafka-event-catalog.md's {@code UserSuspended} row exactly:
 * platform-scoped (identity.users has no single tenant), aggregate
 * {@code users}, payload {@code user_id, reason, actor_user_id}.
 */
public record UserSuspendedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID userId,
    String reason,
    UUID actorUserId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserSuspended";
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
        return "users";
    }
}
