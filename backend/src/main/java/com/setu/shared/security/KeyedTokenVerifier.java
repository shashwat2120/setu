package com.setu.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;

import java.security.Key;
import java.security.PublicKey;
import java.time.Instant;
import java.util.UUID;

/**
 * Verifies ES256 access tokens against the issuer's public keys, found by the token's {@code kid} through a {@link JwtKeySource}.
 * <p>
 * Strict on purpose: only ES256 is accepted (so a token cannot name a weaker algorithm, an unsigned one, or an HMAC one keyed with the
 * public key), a token with no key id or an unknown one is refused, and issuer, audience and expiry are all checked. A token that fails
 * any check is rejected, never treated as anonymous-but-valid.
 */
public class KeyedTokenVerifier implements TokenVerifier {

    public static final String ALGORITHM = "ES256";
    static final String STEP_UP_CLAIM = "sup";
    static final String FAMILY_CLAIM = "fam";

    private final JwtKeySource keys;
    private final String issuer;
    private final String audience;

    public KeyedTokenVerifier(JwtKeySource keys, String issuer, String audience) {
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
    }

    @Override
    public AccessTokenClaims verify(String token) throws TokenRejectedException {
        if (token == null || token.isBlank()) {
            throw new TokenRejectedException("no token");
        }
        try {
            Jws<Claims> jws = Jwts.parser()
                .keyLocator(new LocatorAdapter<Key>() {
                    @Override
                    protected Key locate(JwsHeader header) {
                        String kid = header.getKeyId();
                        if (kid == null || kid.isBlank()) {
                            throw new JwtException("token has no key id");
                        }
                        PublicKey key = keys.publicKey(kid).orElseThrow(() -> new JwtException("unknown signing key"));
                        return key;
                    }
                })
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token);
            if (!ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
                throw new TokenRejectedException("algorithm not allowed");
            }
            Claims claims = jws.getPayload();
            Long stepUpEpoch = claims.get(STEP_UP_CLAIM, Long.class);
            String family = claims.get(FAMILY_CLAIM, String.class);
            return new AccessTokenClaims(
                UUID.fromString(claims.getSubject()),
                claims.getId(),
                claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant(),
                stepUpEpoch == null ? null : Instant.ofEpochSecond(stepUpEpoch),
                family == null ? null : UUID.fromString(family));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new TokenRejectedException("token rejected", e);
        }
    }
}
