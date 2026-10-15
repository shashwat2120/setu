package com.setu.guardianaccess.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** A guardian's access to a student's data was revoked. From the moment a service applies this, the grant no longer authorizes anything there. */
public record GuardianAccessRevokedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID grantId,
    UUID studentId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "GuardianAccessRevoked";
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
