package com.setu.shared.security;

import java.util.Optional;

/**
 * What the gateway and every non-Identity service does with a bearer token: verify the signature against Identity's published keys, then
 * check that the login has not been revoked. No database, no shared secret. Anything that fails either step yields empty, never a
 * partially-trusted result.
 */
public class RemoteTokenAuthenticator {

    private final TokenVerifier verifier;
    private final LoginRevocations revocations;

    public RemoteTokenAuthenticator(TokenVerifier verifier, LoginRevocations revocations) {
        this.verifier = verifier;
        this.revocations = revocations;
    }

    public Optional<AccessTokenClaims> authenticate(String bearerToken) {
        try {
            AccessTokenClaims claims = verifier.verify(bearerToken);
            return revocations.isRevoked(claims) ? Optional.empty() : Optional.of(claims);
        } catch (TokenRejectedException e) {
            return Optional.empty();
        }
    }
}
