package com.setu.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** The authenticated principal attached to the SecurityContext by JwtAuthenticationFilter. Roles/tenant are deliberately NOT carried here — AuthorizationService resolves those fresh per request (see JwtService's javadoc). {@code stepUpAt} is null unless the token was issued right after a fresh passkey reauthentication. */
public record UserPrincipal(UUID userId, String tokenId, Instant stepUpAt) {
}
