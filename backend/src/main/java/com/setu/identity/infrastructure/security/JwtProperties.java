package com.setu.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token issuance/validation config. Access tokens are signed ES256 (asymmetric) so the services Sprint 6 splits out can verify them
 * from the published JWKS without ever sharing a secret; see {@link JwtKeyStore}.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public class JwtProperties {

    /**
     * Base64-encoded HMAC secret, >= 256 bits. No longer signs tokens; it only keys the password-reset-code HMAC (ResetCodeHasher). The
     * default below is DEV-ONLY filler so the app boots without extra setup; every real deployment MUST override via JWT_SECRET.
     */
    private String secret = "ZGV2LW9ubHktc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi1yZXBsYWNlLW1l";

    /** Base64 PKCS#8 DER of the EC P-256 private key that signs access tokens. Blank = generate an ephemeral key (dev/tests only). */
    private String signingKey = "";

    /** Base64 X.509 DER public keys of retired signing keys, still published and accepted during a rotation. */
    private java.util.List<String> previousPublicKeys = new java.util.ArrayList<>();

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    public java.util.List<String> getPreviousPublicKeys() {
        return previousPublicKeys;
    }

    public void setPreviousPublicKeys(java.util.List<String> previousPublicKeys) {
        this.previousPublicKeys = previousPublicKeys;
    }

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
