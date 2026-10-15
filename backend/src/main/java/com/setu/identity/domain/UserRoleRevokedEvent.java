package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** A role assignment ended. The counterpart of {@link UserRoleAssignedEvent}; a consumer drops the assignment from its read model. */
public record UserRoleRevokedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID tenantId,
    UUID userRoleId,
    UUID userId,
    String roleCode,
    UUID actorUserId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserRoleRevoked";
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
    public String aggregateType() {
        return "user_roles";
    }
}
