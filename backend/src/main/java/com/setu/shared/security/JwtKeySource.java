package com.setu.shared.security;

import java.security.PublicKey;
import java.util.Optional;

/** Where a verifier finds the public key for a token's key id: the issuer's own key store, or the issuer's published JWKS. */
public interface JwtKeySource {

    Optional<PublicKey> publicKey(String keyId);
}
