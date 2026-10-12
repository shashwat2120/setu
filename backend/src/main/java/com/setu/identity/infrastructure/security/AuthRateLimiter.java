package com.setu.identity.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Throttles the unauthenticated credential endpoints ({@code /auth/login}, {@code /auth/password/reset/initiate}).
 * The per-account lockout in {@code User} only counts failures for an account that exists, so it cannot stop one
 * password sprayed across many accounts, endless guesses at unknown emails, a flood of slow Argon2id hashes, or
 * someone deliberately locking a real student out. This adds two independent fixed-window counters per endpoint —
 * one per (email), one per client IP — and a request is refused when EITHER is over its limit.
 * <p>
 * Same design stance as {@code AiRateLimiter}: Redis holds only an expiring counter, never a source of truth, and it
 * fails OPEN if Redis is unreachable (a Redis outage must not lock everyone out of logging in; the per-account
 * lockout still applies). Emails are stored only as a SHA-256 hash so no address sits in Redis. The IP limit is a
 * loose ceiling on purpose — behind a campus NAT many real users share one address — while the email limit is the
 * tight one.
 */
@Component
public class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

    /** {@code firstBlock} is true only for the first refused request in a window, so callers can audit once instead of letting an attacker fill the audit table. */
    public record Decision(boolean allowed, long retryAfterSeconds, boolean firstBlock) {
        static final Decision ALLOWED = new Decision(true, 0, false);
    }

    private final StringRedisTemplate redisTemplate;
    private final int loginPerEmail;
    private final int loginPerIp;
    private final Duration loginWindow;
    private final int resetPerEmail;
    private final int resetPerIp;
    private final Duration resetWindow;

    public AuthRateLimiter(StringRedisTemplate redisTemplate,
                            @Value("${app.security.rate-limit.login.per-email:10}") int loginPerEmail,
                            @Value("${app.security.rate-limit.login.per-ip:120}") int loginPerIp,
                            @Value("${app.security.rate-limit.login.window-seconds:60}") long loginWindowSeconds,
                            @Value("${app.security.rate-limit.password-reset.per-email:3}") int resetPerEmail,
                            @Value("${app.security.rate-limit.password-reset.per-ip:20}") int resetPerIp,
                            @Value("${app.security.rate-limit.password-reset.window-seconds:900}") long resetWindowSeconds) {
        this.redisTemplate = redisTemplate;
        this.loginPerEmail = loginPerEmail;
        this.loginPerIp = loginPerIp;
        this.loginWindow = Duration.ofSeconds(loginWindowSeconds);
        this.resetPerEmail = resetPerEmail;
        this.resetPerIp = resetPerIp;
        this.resetWindow = Duration.ofSeconds(resetWindowSeconds);
    }

    public Decision checkLogin(String email, String clientIp) {
        return check("login", email, clientIp, loginPerEmail, loginPerIp, loginWindow);
    }

    public Decision checkPasswordReset(String email, String clientIp) {
        return check("pwreset", email, clientIp, resetPerEmail, resetPerIp, resetWindow);
    }

    private Decision check(String scope, String email, String clientIp, int perEmail, int perIp, Duration window) {
        try {
            Decision byEmail = consume("auth:" + scope + ":email:" + hash(email), perEmail, window);
            Decision byIp = consume("auth:" + scope + ":ip:" + (clientIp == null ? "unknown" : clientIp), perIp, window);
            if (byEmail.allowed() && byIp.allowed()) {
                return Decision.ALLOWED;
            }
            long retryAfter = Math.max(byEmail.retryAfterSeconds(), byIp.retryAfterSeconds());
            return new Decision(false, Math.max(retryAfter, 1), byEmail.firstBlock() || byIp.firstBlock());
        } catch (Exception e) {
            log.warn("auth rate limiter could not reach Redis, failing open for this request", e);
            return Decision.ALLOWED;
        }
    }

    private Decision consume(String key, int limit, Duration window) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return Decision.ALLOWED;
        }
        if (count == 1L) {
            redisTemplate.expire(key, window);
        }
        if (count <= limit) {
            return Decision.ALLOWED;
        }
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            // a counter without an expiry would block this caller forever — repair it
            redisTemplate.expire(key, window);
            ttl = window.toSeconds();
        }
        return new Decision(false, ttl, count == limit + 1L);
    }

    /** First 12 hex chars of the email hash — enough to correlate audit rows, not enough to recover or confirm an address. */
    public static String shortHash(String email) {
        return hash(email).substring(0, 12);
    }

    static String hash(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
