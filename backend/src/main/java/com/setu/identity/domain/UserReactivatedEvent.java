package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Not in the original kafka-event-catalog.md pass — added alongside the
 * account-lifecycle work that introduced {@link UserAccessService#reactivate}
 * (docs/kafka-event-catalog.md updated in the same change). Mirrors
 * {@link UserSuspendedEvent}'s shape exactly: platform-scoped, aggregate
 * {@code users}, payload {@code user_id, reason, actor_user_id}.
 */
public record UserReactivatedEvent(
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
        return "UserReactivated";
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
