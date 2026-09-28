package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches docs/kafka-event-catalog.md's {@code PasskeyRevoked} row exactly:
 * platform-scoped, aggregate {@code passkeys}, payload
 * {@code passkey_id, user_id, revoked_by}.
 */
public record PasskeyRevokedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID passkeyId,
    UUID userId,
    UUID revokedBy
) implements DomainEvent {

    @Override
    public String eventType() {
        return "PasskeyRevoked";
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
        return "passkeys";
    }
}
