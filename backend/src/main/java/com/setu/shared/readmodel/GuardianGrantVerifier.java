package com.setu.shared.readmodel;

import java.util.UUID;

/**
 * The authoritative check of one guardian grant, put to the service that owns the grants (Academic). The local copy of the grants is
 * current within seconds, which is not good enough to say "yes" to a guardian whose access may have been taken away a moment ago: a yes is
 * confirmed here first. See docs/contracts/data-ownership.md.
 */
public interface GuardianGrantVerifier {

    enum Verdict {
        /** The owner confirmed the grant is active right now. */
        ACTIVE,
        /** The owner says it is not: revoked, expired or never there. */
        NOT_ACTIVE,
        /** The owner could not be asked or did not answer in time. This is not a "no" from the owner, and not a "yes" either. */
        UNAVAILABLE
    }

    Verdict verify(UUID guardianUserId, UUID studentProfileId, UUID tenantId, String permissionCode);
}
