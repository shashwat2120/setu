package com.setu.gateway;

import com.setu.shared.discovery.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Health-aware discovery without a registry server. Instances come from two places:
 * <ul>
 *   <li><b>announced</b>: services renew a Redis key every few seconds ({@link ServiceRegistry}); one that stops, for any reason, expires
 *       within the TTL and disappears from the set;</li>
 *   <li><b>configured</b>: the instances in {@code app.gateway.services.*.instances}, always included so the gateway still works with Redis
 *       down or a service that does not announce itself.</li>
 * </ul>
 * An instance the gateway could not connect to goes to the back of the list for {@code quarantine} (passive health: a failure is noticed on
 * the request that hit it, not on a poll). Instances are rotated round-robin so load spreads. The Redis lookup is cached for a couple of seconds so routing
 * costs no Redis scan per request; a Redis failure keeps the last answer and the configured list.
 */
public class RedisServiceDirectory implements ServiceDirectory {

    private static final Logger log = LoggerFactory.getLogger(RedisServiceDirectory.class);
    private static final Duration CACHE_FOR = Duration.ofSeconds(2);

    private record Cached(List<URI> announced, Instant at) {
    }

    private final StringRedisTemplate redis;
    private final Map<String, List<URI>> configured;
    private final Duration quarantine;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<URI, Instant> quarantinedUntil = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rotation = new ConcurrentHashMap<>();

    public RedisServiceDirectory(StringRedisTemplate redis, Map<String, List<URI>> configured, Duration quarantine, Clock clock) {
        this.redis = redis;
        this.configured = configured;
        this.quarantine = quarantine;
        this.clock = clock;
    }

    @Override
    public List<URI> available(String service) {
        Instant now = clock.instant();
        Set<URI> all = new LinkedHashSet<>(announced(service, now));
        all.addAll(configured.getOrDefault(service, List.of()));
        List<URI> usable = new ArrayList<>();
        List<URI> recentlyFailed = new ArrayList<>();
        for (URI instance : all) {
            Instant until = quarantinedUntil.get(instance);
            (until == null || !until.isAfter(now) ? usable : recentlyFailed).add(instance);
        }
        if (usable.size() > 1) {
            int start = Math.floorMod(rotation.computeIfAbsent(service, s -> new AtomicInteger()).getAndIncrement(), usable.size());
            java.util.Collections.rotate(usable, -start);
        }
        // A quarantined instance is demoted, not banned: it is still offered after the others, so one stray reset on a single-instance service
        // cannot turn into fifteen seconds of 503, and a service that is truly down is reported as 502 rather than hidden.
        usable.addAll(recentlyFailed);
        return usable;
    }

    @Override
    public void reportFailure(String service, URI instance) {
        quarantinedUntil.put(instance, clock.instant().plus(quarantine));
        log.warn("gateway: {} instance {} is unreachable; leaving it out for {}s", service, instance, quarantine.toSeconds());
    }

    private List<URI> announced(String service, Instant now) {
        Cached cached = cache.get(service);
        if (cached != null && Duration.between(cached.at(), now).compareTo(CACHE_FOR) < 0) {
            return cached.announced();
        }
        try {
            List<URI> found = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().match(ServiceRegistry.patternFor(service)).count(100).build();
            try (Cursor<String> keys = redis.scan(options)) {
                while (keys.hasNext()) {
                    String url = redis.opsForValue().get(keys.next());
                    if (url != null && !url.isBlank()) {
                        found.add(URI.create(url.strip()));
                    }
                }
            }
            found.sort(java.util.Comparator.comparing(URI::toString));
            cache.put(service, new Cached(List.copyOf(found), now));
            return found;
        } catch (RuntimeException e) {
            log.warn("gateway: could not read announced {} instances from Redis ({}); using the last known and configured ones", service, e.toString());
            return cached == null ? List.of() : cached.announced();
        }
    }
}
