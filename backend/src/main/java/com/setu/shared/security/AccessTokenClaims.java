package com.setu.shared.security;

import java.time.Instant;
import java.util.UUID;

/**
 * What a verified access token says: who, when it expires, whether a fresh passkey step-up is on it, and which login (session family) it
 * was issued for. Roles and tenant are deliberately NOT in the token: each service resolves them fresh, so a role change does not wait for
 * the token to expire.
 *
 * @param familyId the login the token is bound to, so ending that login revokes the token; null only for tokens minted directly by tests and tooling
 */
public record AccessTokenClaims(UUID userId, String jti, Instant issuedAt, Instant expiresAt, Instant stepUpAt, UUID familyId) {
}
