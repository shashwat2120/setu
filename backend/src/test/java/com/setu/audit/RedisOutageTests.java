package com.setu.audit;

import com.setu.aiexplain.infrastructure.ratelimit.AiRateLimiter;
import com.setu.identity.infrastructure.security.AuthRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Canonical spec §9: Redis is never the source of truth. For the two limiters that use it, that means an unreachable Redis must not
 * lock everyone out of signing in or asking the advisor (they fail open, and say so in the log); the per-account lockout and the
 * authorization checks do not depend on Redis at all.
 */
class RedisOutageTests {

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisThatIsDown(RuntimeException failure) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(failure);
        return redis;
    }

    @Test
    void signInAndPasswordResetAreNotBlockedWhenRedisIsDown() {
        var limiter = new AuthRateLimiter(redisThatIsDown(new RedisConnectionFailureException("connection refused")), 10, 120, 60, 3, 20, 900, 10, 60, 900);

        assertThat(limiter.checkLogin("someone@example.com", "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.checkPasswordReset("someone@example.com", "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.checkPasswordResetConfirm("someone@example.com", "10.0.0.1").allowed()).isTrue();
    }

    @Test
    void aSlowRedisTimeoutAlsoFailsOpen() {
        var limiter = new AuthRateLimiter(redisThatIsDown(new QueryTimeoutException("timed out")), 10, 120, 60, 3, 20, 900, 10, 60, 900);

        assertThat(limiter.checkLogin("someone@example.com", "10.0.0.1").allowed()).isTrue();
    }

    @Test
    void theAdvisorRateLimitFailsOpenWhenRedisIsDown() {
        var limiter = new AiRateLimiter(redisThatIsDown(new RedisConnectionFailureException("connection refused")), 20, 60);

        assertThat(limiter.tryAcquire(UUID.randomUUID(), UUID.randomUUID())).isTrue();
    }
}
