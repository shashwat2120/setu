package com.setu.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Per-caller request ceiling at the edge: a fixed one-minute window counted in Redis. Same stance as the other limiters in this codebase:
 * Redis holds only an expiring counter, never a source of truth, and the limiter fails OPEN when Redis is unreachable (an outage of the
 * cache must not take the whole platform offline; the services behind keep their own limits for the sensitive endpoints).
 */
public class GatewayRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimiter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final StringRedisTemplate redis;

    public GatewayRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @param bucket who is being counted, for example {@code user:<id>} or {@code ip:<address>}, within one route */
    public Decision check(String routeId, String bucket, int limitPerMinute) {
        String key = "setu:gw:rl:" + routeId + ":" + bucket;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return Decision.ALLOWED;
            }
            if (count == 1L) {
                redis.expire(key, WINDOW);
            }
            if (count <= limitPerMinute) {
                return Decision.ALLOWED;
            }
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            if (ttl == null || ttl < 0) {
                redis.expire(key, WINDOW);   // a counter with no expiry would block this caller forever: repair it
                ttl = WINDOW.toSeconds();
            }
            return new Decision(false, Math.max(ttl, 1));
        } catch (RuntimeException e) {
            log.warn("gateway rate limiter could not reach Redis, failing open for this request: {}", e.toString());
            return Decision.ALLOWED;
        }
    }
}
