package com.setu.guardianaccess.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A guardian was given access to one student's data, for a set of permissions and until an optional expiry. {@code guardianUserId} is the
 * account the guardian signs in with, so a service that authorizes "this signed-in guardian may see this student" can answer from its own
 * copy. Written to the outbox with the grant, so the event exists if and only if the grant committed.
 */
public record GuardianAccessGrantedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID grantId,
    UUID studentId,
    UUID guardianUserId,
    Instant expiresAt,
    List<String> permissions
) implements DomainEvent {

    @Override
    public String eventType() {
        return "GuardianAccessGranted";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "guardianaccess";
    }

    @Override
    public String aggregateType() {
        return "guardian_access_grants";
    }
}
