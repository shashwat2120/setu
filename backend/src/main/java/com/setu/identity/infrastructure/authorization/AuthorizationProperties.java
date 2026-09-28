package com.setu.identity.infrastructure.authorization;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.authorization")
public class AuthorizationProperties {

    /** How long a step-up (fresh passkey reauthentication) stays valid before a sensitive operation demands another one. */
    private long stepUpValidityMinutes = 5;

    public long getStepUpValidityMinutes() {
        return stepUpValidityMinutes;
    }

    public void setStepUpValidityMinutes(long stepUpValidityMinutes) {
        this.stepUpValidityMinutes = stepUpValidityMinutes;
    }
}
