package com.setu.identity.infrastructure.web;

import com.setu.identity.infrastructure.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The refresh token never appears in a JSON body or localStorage (Day 3
 * browser-strategy instruction) — only as this cookie. Secure + HttpOnly +
 * SameSite=Strict: HttpOnly blocks any JS (including XSS payloads) from
 * reading it, Secure requires TLS (browsers exempt http://localhost from
 * this for local dev), SameSite=Strict stops it being sent on cross-site
 * requests at all — the main reason CSRF protection isn't separately needed
 * on the auth endpoints (see SecurityConfig).
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class RefreshCookieFactory {

    static final String COOKIE_NAME = "setu_rt";

    private final JwtProperties properties;

    public RefreshCookieFactory(JwtProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie create(String rawRefreshToken) {
        return ResponseCookie.from(COOKIE_NAME, rawRefreshToken)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/auth")
            .maxAge(Duration.ofDays(properties.getRefreshTokenTtlDays()))
            .build();
    }

    public ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/auth")
            .maxAge(Duration.ZERO)
            .build();
    }
}
