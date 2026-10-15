package com.setu.shared.security;

import java.util.UUID;

/**
 * Which logins and accounts have been cut off, as seen by services that do not own sessions. Identity writes it when a login ends
 * (logout, refresh-token reuse, password reset, suspension, deactivation); the gateway and every other service read it before trusting
 * a token whose signature and expiry are fine.
 * <p>
 * It only needs to remember for one access-token lifetime, because after that the token is expired anyway. It is an acceleration of
 * "this token is dead" for the 15-minute window, not a source of truth: Identity's own database remains the authority.
 */
public interface LoginRevocations {

    /** This login (session family) has ended: every access token bound to it is dead. */
    void revokeLogin(UUID familyId);

    /** Every access token issued to this account up to now is dead (suspension, deactivation, password reset). */
    void revokeAccount(UUID userId);

    /** Whether this token must be refused. Fails closed: if the answer cannot be determined it is {@code true}. */
    boolean isRevoked(AccessTokenClaims claims);
}
