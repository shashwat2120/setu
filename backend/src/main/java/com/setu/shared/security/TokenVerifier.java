package com.setu.shared.security;

/**
 * Checks an access token and returns what it says, or throws {@link TokenRejectedException}. Every service uses one of these to
 * authenticate a request; none needs the signing secret, only the issuer's public keys.
 */
public interface TokenVerifier {

    AccessTokenClaims verify(String token) throws TokenRejectedException;
}
