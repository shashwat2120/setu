package com.setu.gateway;

import com.setu.shared.discovery.ServiceRegistrar;
import com.setu.shared.discovery.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Discovery against the real Redis: services announce themselves, vanish when they stop, and failing instances are left out for a while. */
@SpringBootTest
class RedisServiceDirectoryTests {

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @Autowired
    StringRedisTemplate redis;

    private final String service = "test-" + UUID.randomUUID().toString().substring(0, 8);
    private final MutableClock clock = new MutableClock();

    @AfterEach
    void cleanUp() {
        var keys = redis.keys(ServiceRegistry.patternFor(service));
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private RedisServiceDirectory directory(List<URI> configured) {
        return new RedisServiceDirectory(redis, Map.of(service, configured), Duration.ofSeconds(15), clock);
    }

    @Test
    void configuredInstancesAreAlwaysAvailable() {
        assertThat(directory(List.of(URI.create("http://configured:1"))).available(service)).containsExactly(URI.create("http://configured:1"));
        assertThat(directory(List.of()).available("a-service-nobody-knows")).isEmpty();
    }

    @Test
    void anAnnouncedInstanceJoinsAndLeavesWhenItsKeyExpiresOrIsRemoved() {
        RedisServiceDirectory directory = directory(List.of(URI.create("http://configured:1")));
        ServiceRegistrar registrar = new ServiceRegistrar(redis, service, "http://announced:2", "instance-1");

        registrar.heartbeat();
        clock.now = clock.now.plusSeconds(3);   // past the directory's two-second cache
        assertThat(directory.available(service)).containsExactlyInAnyOrder(URI.create("http://configured:1"), URI.create("http://announced:2"));

        assertThat(redis.getExpire(ServiceRegistry.keyFor(service, "instance-1"))).isBetween(1L, ServiceRegistry.TTL.toSeconds());

        registrar.deregister();   // a clean shutdown removes the key at once
        clock.now = clock.now.plusSeconds(3);
        assertThat(directory.available(service)).containsExactly(URI.create("http://configured:1"));
    }

    @Test
    void anAnnouncedInstanceThatStopsRenewingDropsOutWhenItsKeyExpires() {
        RedisServiceDirectory directory = directory(List.of());
        redis.opsForValue().set(ServiceRegistry.keyFor(service, "crashed"), "http://crashed:3", Duration.ofMillis(300));
        assertThat(directory.available(service)).containsExactly(URI.create("http://crashed:3"));

        long deadline = System.currentTimeMillis() + 5000;
        while (redis.hasKey(ServiceRegistry.keyFor(service, "crashed")) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        clock.now = clock.now.plusSeconds(3);
        assertThat(directory.available(service)).isEmpty();
    }

    @Test
    void aFailedInstanceGoesToTheBackThenReturnsToTheRotationAfterTheQuarantine() {
        URI one = URI.create("http://one:1");
        URI two = URI.create("http://two:2");
        RedisServiceDirectory directory = directory(List.of(one, two));

        directory.reportFailure(service, one);
        assertThat(directory.available(service)).containsExactly(two, one);   // demoted, not banned
        assertThat(directory.available(service)).containsExactly(two, one);

        clock.now = clock.now.plusSeconds(16);
        assertThat(directory.available(service)).containsExactlyInAnyOrder(one, two);
    }

    @Test
    void instancesAreRotatedSoLoadSpreads() {
        URI one = URI.create("http://one:1");
        URI two = URI.create("http://two:2");
        RedisServiceDirectory directory = directory(List.of(one, two));
        URI first = directory.available(service).get(0);
        URI second = directory.available(service).get(0);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void whenRedisFailsTheConfiguredInstancesStillWork() {
        StringRedisTemplate broken = new StringRedisTemplate() {
            @Override
            public <T> T execute(org.springframework.data.redis.core.RedisCallback<T> action) {
                throw new org.springframework.data.redis.RedisConnectionFailureException("down");
            }

            @Override
            public org.springframework.data.redis.core.Cursor<String> scan(org.springframework.data.redis.core.ScanOptions options) {
                throw new org.springframework.data.redis.RedisConnectionFailureException("down");
            }
        };
        RedisServiceDirectory directory = new RedisServiceDirectory(broken, Map.of(service, List.of(URI.create("http://configured:1"))),
            Duration.ofSeconds(15), clock);
        assertThat(directory.available(service)).containsExactly(URI.create("http://configured:1"));
    }

    @Test
    void theRateLimiterCountsPerBucketAndRefusesOverTheLimitWithRetryAfter() {
        GatewayRateLimiter limiter = new GatewayRateLimiter(redis);
        String route = "t-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check(route, "user:a", 3).allowed()).isTrue();
        }
        GatewayRateLimiter.Decision refused = limiter.check(route, "user:a", 3);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(limiter.check(route, "user:b", 3).allowed()).as("another caller has its own count").isTrue();
        redis.delete(List.of("setu:gw:rl:" + route + ":user:a", "setu:gw:rl:" + route + ":user:b"));
    }

    @Test
    void theRateLimiterFailsOpenWhenRedisIsDown() {
        StringRedisTemplate broken = new StringRedisTemplate() {
            @Override
            public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
                throw new org.springframework.data.redis.RedisConnectionFailureException("down");
            }
        };
        assertThat(new GatewayRateLimiter(broken).check("r", "user:a", 1).allowed()).isTrue();
    }
}
