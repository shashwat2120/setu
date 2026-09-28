package com.setu.aiexplain.infrastructure.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Day 14 §17: AI endpoint rate limiting — the first real use of Redis in
 * this codebase (never a source of truth, only a fixed-window counter
 * that expires on its own). Scoped per-user-per-tenant, never global and
 * never per-IP, so one abusive tenant/user cannot exhaust another's quota
 * and a shared NAT/proxy cannot be mistaken for a single caller.
 * <p>
 * Fixed-window via {@code INCR} + {@code EXPIRE NX} — simple and correct
 * for this scope (not a sliding-window/token-bucket algorithm); a burst
 * right at a window boundary can admit slightly more than the nominal
 * limit, a disclosed, acceptable imprecision for an abuse guard, not a
 * billing-grade quota.
 */
@Component
public class AiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AiRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final int limit;
    private final Duration window;

    public AiRateLimiter(StringRedisTemplate redisTemplate,
                          @Value("${app.ai.rate-limit.max-requests:20}") int limit,
                          @Value("${app.ai.rate-limit.window-seconds:60}") long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.limit = limit;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /** True if this (tenant, user) pair may proceed; false if it has exceeded the window's limit. Fails OPEN (allows the request) if Redis itself is unreachable — an AI feature going briefly unmetered is a far smaller risk than a Redis outage taking down every AI response with a 500. */
    public boolean tryAcquire(UUID tenantId, UUID userId) {
        String key = "ai:ratelimit:" + tenantId + ":" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                redisTemplate.expire(key, window);
            }
            return count <= limit;
        } catch (Exception e) {
            log.warn("AI rate limiter could not reach Redis, failing open for this request", e);
            return true;
        }
    }
}
