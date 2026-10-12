package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches docs/kafka-event-catalog.md's {@code UserDeactivated} row exactly:
 * platform-scoped, aggregate {@code users}, payload
 * {@code user_id, actor_user_id, self_initiated}. {@code selfInitiated} is
 * always {@code false} today — only an authorized administrator can
 * deactivate an account in this build; a self-service account-closure flow
 * would set it {@code true} without changing this event's shape.
 */
public record UserDeactivatedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID userId,
    UUID actorUserId,
    boolean selfInitiated
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserDeactivated";
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
