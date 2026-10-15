package com.setu.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** How a non-issuing service finds the issuer's keys: cached, refetched on an unknown key id (rate limited), kept when the issuer is down. */
class JwksKeySourceTests {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicReference<String> document = new AtomicReference<>();
    private final AtomicReference<Boolean> down = new AtomicReference<>(false);
    private KeyPair keyA;
    private KeyPair keyB;
    private JwksKeySource source;

    private static KeyPair newPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String jwks(Map<String, PublicKey> keys) {
        return Jwks.toJson(keys);
    }

    @BeforeEach
    void setUp() throws Exception {
        keyA = newPair();
        keyB = newPair();
        document.set(jwks(Map.of("a", keyA.getPublic())));
        source = new JwksKeySource(() -> {
            fetches.incrementAndGet();
            if (down.get()) {
                throw new java.io.IOException("issuer unreachable");
            }
            return document.get();
        }, clock, Duration.ofMinutes(10), Duration.ofSeconds(30));
    }

    @Test
    void fetchesOnceAndServesFromCacheAfter() {
        assertThat(source.publicKey("a")).contains(keyA.getPublic());
        assertThat(source.publicKey("a")).contains(keyA.getPublic());
        assertThat(source.publicKey("a")).contains(keyA.getPublic());
        assertThat(fetches).hasValue(1);
    }

    @Test
    void anUnknownKeyIdTriggersARefetchThatPicksUpARotation() {
        assertThat(source.publicKey("a")).isPresent();
        Map<String, PublicKey> rotated = new LinkedHashMap<>();
        rotated.put("b", keyB.getPublic());
        rotated.put("a", keyA.getPublic());
        document.set(jwks(rotated));
        clock.now = clock.now.plusSeconds(60);

        assertThat(source.publicKey("b")).contains(keyB.getPublic());
        assertThat(fetches).hasValue(2);
    }

    @Test
    void refetchesAreRateLimitedSoMadeUpKeyIdsCannotHammerTheIssuer() {
        assertThat(source.publicKey("a")).isPresent();
        for (int i = 0; i < 50; i++) {
            assertThat(source.publicKey("made-up-" + i)).isEmpty();
        }
        // The first unknown id may refetch once the window allows; the rest within the window must not.
        assertThat(fetches.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void keepsTheKeysItHoldsWhenTheIssuerGoesDown() {
        assertThat(source.publicKey("a")).isPresent();
        down.set(true);
        clock.now = clock.now.plus(Duration.ofMinutes(11));   // past the refresh interval, so it tries and fails

        assertThat(source.publicKey("a")).contains(keyA.getPublic());
    }

    @Test
    void neverInventsAKeyItHasNotSeenWhileTheIssuerIsDown() {
        down.set(true);
        assertThat(source.publicKey("a")).isEmpty();
    }

    @Test
    void ignoresAnEmptyOrHostileDocumentAndKeepsTheOldKeys() {
        assertThat(source.publicKey("a")).isPresent();
        document.set("{\"keys\":[]}");
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertThat(source.publicKey("a")).contains(keyA.getPublic());
        document.set("not json at all");
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertThat(source.publicKey("a")).contains(keyA.getPublic());
    }

    @Test
    void theDocumentContainsOnlyPublicParametersAndRoundTrips() {
        String json = jwks(Map.of("a", keyA.getPublic()));
        assertThat(json).doesNotContain("\"d\"").contains("\"kty\":\"EC\"").contains("\"crv\":\"P-256\"").contains("\"alg\":\"ES256\"");
        assertThat(Jwks.parse(json)).containsEntry("a", keyA.getPublic());
    }

    @Test
    void parseSkipsKeysThatAreNotEcP256SigningKeys() {
        String json = "{\"keys\":["
            + "{\"kty\":\"RSA\",\"kid\":\"rsa\",\"n\":\"AQAB\",\"e\":\"AQAB\"},"
            + "{\"kty\":\"EC\",\"crv\":\"P-384\",\"kid\":\"p384\",\"x\":\"AA\",\"y\":\"AA\"},"
            + "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"AA\",\"y\":\"AA\"},"
            + "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"offcurve\",\"x\":\"AQ\",\"y\":\"AQ\"},"
            + "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"enc\",\"use\":\"enc\",\"x\":\"AA\",\"y\":\"AA\"}]}";
        assertThat(Jwks.parse(json)).isEmpty();
    }
}
