package com.setu.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.security.webauthn")
public class WebAuthnProperties {

    /** The Relying Party ID — must be this domain or a registrable parent of it, and must match across registration and authentication ceremonies. */
    private String rpId = "localhost";

    private String rpName = "Setu";

    /** Every origin a client is allowed to present this RP as. */
    private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:3000");

    private long challengeTtlSeconds = 300;

    public String getRpId() {
        return rpId;
    }

    public void setRpId(String rpId) {
        this.rpId = rpId;
    }

    public String getRpName() {
        return rpName;
    }

    public void setRpName(String rpName) {
        this.rpName = rpName;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public long getChallengeTtlSeconds() {
        return challengeTtlSeconds;
    }

    public void setChallengeTtlSeconds(long challengeTtlSeconds) {
        this.challengeTtlSeconds = challengeTtlSeconds;
    }
}
