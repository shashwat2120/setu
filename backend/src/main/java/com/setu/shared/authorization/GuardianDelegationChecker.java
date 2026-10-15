package com.setu.shared.authorization;

import java.time.Instant;
import java.util.UUID;

/**
 * The port AuthorizationService calls to evaluate the guardian delegation
 * path (Day 4 instruction §5). Declared here, in {@code shared}, purely as a
 * contract — the implementation composes identity (resolve user -&gt;
 * person), directory (resolve person -&gt; guardian), and guardianaccess
 * (resolve grant + permission), three separate modules, so it can't live
 * inside any single one of them without that module depending on the
 * others. It now lives in guardianaccess, with the grants it reads: see {@code com.setu.guardianaccess.infrastructure.authorization.
 * GuardianDelegationAdapter} for the actual composition.
 */
public interface GuardianDelegationChecker {

    boolean hasActiveDelegatedAccess(UUID guardianUserId, UUID studentProfileId, UUID tenantId,
                                      String permissionCode, Instant now);
}
