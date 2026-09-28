package com.setu.identity.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates short-lived access tokens. Claims are deliberately
 * minimal ({@code sub}, {@code jti}, and now {@code sup} — see below);
 * tenant/role claims are still deliberately NOT carried in the token —
 * AuthorizationService resolves those fresh from identity.user_roles on
 * every request (Day 4), so a role/tenant change takes effect immediately
 * rather than only after the old token expires. Signature (HS256), issuer,
 * audience, and expiry are all verified on every parse; a token failing any
 * one of those checks throws rather than being silently treated as
 * anonymous.
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

    private static final String STEP_UP_CLAIM = "sup";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secretBytes = decodeSecret(properties.getSecret());
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    private static byte[] decodeSecret(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
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
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES);
        var builder = Jwts.builder()
            .subject(userId.toString())
            .issuer(properties.getIssuer())
            .audience().add(properties.getAudience()).and()
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .id(UUID.randomUUID().toString());
        if (stepUpAt != null) {
            builder.claim(STEP_UP_CLAIM, stepUpAt.getEpochSecond());
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    /** Throws a JwtException subtype (ExpiredJwtException, SignatureException, ...) on any validation failure — never returns a partially-trusted result. */
    public AccessTokenClaims validate(String token) throws JwtException {
        Jws<Claims> jws = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(properties.getIssuer())
            .requireAudience(properties.getAudience())
            .build()
            .parseSignedClaims(token);
        Claims claims = jws.getPayload();
        Long stepUpEpoch = claims.get(STEP_UP_CLAIM, Long.class);
        Instant stepUpAt = stepUpEpoch == null ? null : Instant.ofEpochSecond(stepUpEpoch);
        return new AccessTokenClaims(
            UUID.fromString(claims.getSubject()),
            claims.getId(),
            claims.getIssuedAt().toInstant(),
            claims.getExpiration().toInstant(),
            stepUpAt);
    }

    public record AccessTokenClaims(UUID userId, String jti, Instant issuedAt, Instant expiresAt, Instant stepUpAt) {
    }
}
