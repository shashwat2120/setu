package com.setu.identity.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A role was granted to an account (tenant-scoped, or platform-scoped when {@code tenantId} is null). The other services keep a local read
 * model of who holds which role, built from this and {@link UserRoleRevokedEvent}, so they authorize without calling Identity per request.
 * {@code actorUserId} is null for bootstrap grants (the first account of a tenant, demo data).
 */
public record UserRoleAssignedEvent(
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
        return "UserRoleAssigned";
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
