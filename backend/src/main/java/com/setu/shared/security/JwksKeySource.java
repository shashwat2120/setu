package com.setu.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds the issuer's public keys from its published JWKS, for every service that is not the issuer.
 * <ul>
 *   <li>Keys are cached; an <b>unknown key id</b> triggers one refetch, which is how a rotation is picked up without a restart.</li>
 *   <li>Refetches are rate limited ({@code minRefetchInterval}), so a stream of tokens with made-up key ids cannot turn this service into a
 *       request amplifier against the issuer.</li>
 *   <li>If the issuer is unreachable the keys already held keep working (stale-if-error); a key that was never seen is simply refused.</li>
 * </ul>
 */
public class JwksKeySource implements JwtKeySource {

    private static final Logger log = LoggerFactory.getLogger(JwksKeySource.class);

    /** Fetches the JWKS document text; throws on any failure. */
    @FunctionalInterface
    public interface Fetcher {
        String fetch() throws Exception;
    }

    private record Snapshot(Map<String, PublicKey> keys, Instant fetchedAt) {
    }

    private final Fetcher fetcher;
    private final Clock clock;
    private final Duration refreshInterval;
    private final Duration minRefetchInterval;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(Map.of(), Instant.EPOCH));
    private volatile Instant lastAttempt = Instant.EPOCH;

    public JwksKeySource(Fetcher fetcher, Clock clock, Duration refreshInterval, Duration minRefetchInterval) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
        this.minRefetchInterval = minRefetchInterval;
    }

    @Override
    public Optional<PublicKey> publicKey(String keyId) {
        Snapshot current = snapshot.get();
        Instant now = clock.instant();
        boolean stale = Duration.between(current.fetchedAt(), now).compareTo(refreshInterval) > 0;
        PublicKey known = current.keys().get(keyId);
        if (known != null && !stale) {
            return Optional.of(known);
        }
        if (canAttempt(now)) {
            refresh(now);
            current = snapshot.get();
            known = current.keys().get(keyId);
        }
        return Optional.ofNullable(known);
    }

    private synchronized boolean canAttempt(Instant now) {
        if (Duration.between(lastAttempt, now).compareTo(minRefetchInterval) < 0) {
            return false;
        }
        lastAttempt = now;
        return true;
    }

    private void refresh(Instant now) {
        try {
            Map<String, PublicKey> fetched = Jwks.parse(fetcher.fetch());
            if (fetched.isEmpty()) {
                log.warn("the issuer's JWKS contained no usable signing keys; keeping the keys already held");
                return;
            }
            snapshot.set(new Snapshot(Map.copyOf(fetched), now));
        } catch (Exception e) {
            log.warn("could not fetch the issuer's JWKS ({}); keeping the keys already held", e.toString());
        }
    }
}
