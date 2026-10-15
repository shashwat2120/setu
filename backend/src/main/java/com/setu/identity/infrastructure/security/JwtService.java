package com.setu.identity.infrastructure.security;

import com.setu.shared.security.AccessTokenClaims;
import com.setu.shared.security.KeyedTokenVerifier;
import com.setu.shared.security.TokenRejectedException;
import io.jsonwebtoken.Jwts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates short-lived access tokens. Claims are deliberately
 * minimal ({@code sub}, {@code jti}, {@code fam}, and {@code sup} — see below);
 * tenant/role claims are still deliberately NOT carried in the token —
 * AuthorizationService resolves those fresh from identity.user_roles on
 * every request (Day 4), so a role/tenant change takes effect immediately
 * rather than only after the old token expires.
 * <p>
 * Tokens are signed ES256 with the Identity service's private key and carry its {@code kid}; every other service verifies them against the
 * public keys published at {@code /.well-known/jwks.json} and never holds a signing secret (Sprint 6A.2). Signature, algorithm, issuer,
 * audience and expiry are all verified on every parse; a token failing any one of those checks is rejected, never silently treated as
 * anonymous.
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

    private static final String STEP_UP_CLAIM = "sup";
    /** The login (session family) this token came from; see {@link com.setu.identity.application.LoginLivenessService}. */
    private static final String FAMILY_CLAIM = "fam";

    private final JwtProperties properties;
    private final JwtKeyStore keyStore;
    private final KeyedTokenVerifier verifier;

    public JwtService(JwtProperties properties, JwtKeyStore keyStore) {
        this.properties = properties;
        this.keyStore = keyStore;
        this.verifier = new KeyedTokenVerifier(keyStore, properties.getIssuer(), properties.getAudience());
    }

    public String issueAccessToken(UUID userId) {
        return issueAccessToken(userId, null);
    }

    /**
     * {@code stepUpAt} is non-null only when this token is issued
     * immediately after a fresh passkey reauthentication (see
     * PasskeyAuthenticationService#finishStepUp) — AuthorizationContext
     * checks it's both present and recent before allowing a step-up-gated
     * operation (Day 4 instruction §8).
     */
    public String issueAccessToken(UUID userId, Instant stepUpAt) {
        return issueAccessToken(userId, stepUpAt, null);
    }

    /**
     * {@code familyId} binds the token to the login (session family) it was issued for, so ending that login (logout, suspension,
     * refresh-token reuse) stops the token working at once instead of when it expires. Every token issued to a real login carries one;
     * only tokens minted directly by tests and tooling go without.
     */
    public String issueAccessToken(UUID userId, Instant stepUpAt, UUID familyId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES);
        var builder = Jwts.builder()
            .header().keyId(keyStore.signingKeyId()).and()
            .subject(userId.toString())
            .issuer(properties.getIssuer())
            .audience().add(properties.getAudience()).and()
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .id(UUID.randomUUID().toString());
        if (stepUpAt != null) {
            builder.claim(STEP_UP_CLAIM, stepUpAt.getEpochSecond());
        }
        if (familyId != null) {
            builder.claim(FAMILY_CLAIM, familyId.toString());
        }
        return builder.signWith(keyStore.signingKey(), Jwts.SIG.ES256).compact();
    }

    /** Throws {@link TokenRejectedException} on any validation failure — never returns a partially-trusted result. */
    public AccessTokenClaims validate(String token) throws TokenRejectedException {
        return verifier.verify(token);
    }
}
