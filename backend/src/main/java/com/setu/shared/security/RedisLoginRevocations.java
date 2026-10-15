package com.setu.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link LoginRevocations} in Redis: {@code setu:revoked:login:<familyId>} and {@code setu:revoked:account:<userId>} (the latter holds the
 * revocation time, so a token issued after it, for instance after the person logs in again, is not caught). Keys expire after the access
 * token lifetime plus a minute of clock skew, so the set never grows beyond the logins ended in the last quarter hour.
 * <p>
 * Writes by Identity are best effort and logged loudly if they fail (the database is still the authority and the token expires on its
 * own); reads by everyone else fail closed.
 */
public class RedisLoginRevocations implements LoginRevocations {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginRevocations.class);
    static final String LOGIN_PREFIX = "setu:revoked:login:";
    static final String ACCOUNT_PREFIX = "setu:revoked:account:";

    private final StringRedisTemplate redis;
    private final Duration keepFor;
    private final Clock clock;

    public RedisLoginRevocations(StringRedisTemplate redis, Duration accessTokenTtl, Clock clock) {
        this.redis = redis;
        this.keepFor = accessTokenTtl.plus(Duration.ofMinutes(1));
        this.clock = clock;
    }

    @Override
    public void revokeLogin(UUID familyId) {
        write(LOGIN_PREFIX + familyId, "1");
    }

    @Override
    public void revokeAccount(UUID userId) {
        write(ACCOUNT_PREFIX + userId, Long.toString(clock.instant().getEpochSecond()));
    }

    @Override
    public boolean isRevoked(AccessTokenClaims claims) {
        try {
            if (claims.familyId() != null && Boolean.TRUE.equals(redis.hasKey(LOGIN_PREFIX + claims.familyId()))) {
                return true;
            }
            String revokedAt = redis.opsForValue().get(ACCOUNT_PREFIX + claims.userId());
            if (revokedAt == null) {
                return false;
            }
            // Tokens are issued with whole-second timestamps, so a token minted in the same second as the revocation is treated as revoked.
            return !claims.issuedAt().isAfter(Instant.ofEpochSecond(Long.parseLong(revokedAt)));
        } catch (RuntimeException e) {
            log.warn("revocation list unavailable ({}); refusing the token", e.toString());
            return true;
        }
    }

    private void write(String key, String value) {
        try {
            redis.opsForValue().set(key, value, keepFor);
        } catch (RuntimeException e) {
            log.error("could not publish a revocation to Redis ({}); other services will accept the token until it expires", e.toString());
        }
    }
}
