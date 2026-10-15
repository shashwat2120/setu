package com.setu.shared.authorization;

import java.time.Instant;
import java.util.UUID;

/** The principal a non-Identity service attaches to the security context after verifying a bearer token. Roles and tenant are not carried: they are resolved per request. */
public record ServicePrincipal(UUID userId, String tokenId, Instant stepUpAt, UUID familyId) implements AuthenticatedUser {
}
