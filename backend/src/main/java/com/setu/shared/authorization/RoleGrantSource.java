package com.setu.shared.authorization;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where the authorization engine learns who holds which role and what each role may do. Identity answers from its own tables; every other
 * service answers from a local read model fed by Identity's role events, so a permission check never leaves the process.
 */
public interface RoleGrantSource {

    /** The roles the account holds at {@code now}: assignments that have started and not ended. */
    List<RoleGrant> activeGrants(UUID userId, Instant now);

    /** The permission codes granted by any of these roles. */
    Set<String> permissionsOf(Collection<String> roleCodes);
}
