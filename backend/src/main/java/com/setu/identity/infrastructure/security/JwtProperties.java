package com.setu.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token issuance/validation config. HS256 (symmetric) for now — fine
 * for a single monolith validating its own tokens; revisit (RS256, one
 * signing key per service) only if/when Sprint 6 splits this into services
 * that must validate tokens without sharing a symmetric secret.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public class JwtProperties {

    /**
     * Base64-encoded HMAC secret, >= 256 bits. The default below is
     * DEV-ONLY filler so the app boots without extra setup; every real
     * deployment MUST override via JWT_SECRET — see application.yml.
     */
    private String secret = "ZGV2LW9ubHktc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi1yZXBsYWNlLW1l";

    private String issuer = "setu";

    private String audience = "setu-api";

    /** Minutes. Short-lived by design (Day 3 instruction). */
    private long accessTokenTtlMinutes = 15;

    /** Days. The refresh token itself is opaque (see SessionService) — this only bounds the DB row's expires_at. */
    private long refreshTokenTtlDays = 30;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public long getAccessTokenTtlMinutes() {
        return accessTokenTtlMinutes;
    }

    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    public long getRefreshTokenTtlDays() {
        return refreshTokenTtlDays;
    }

    public void setRefreshTokenTtlDays(long refreshTokenTtlDays) {
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }
}
