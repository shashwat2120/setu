package com.setu.shared.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a search question costs once the embedder is wrapped: a repeated question is answered from Redis and not from the provider, a provider that just failed is left alone
 * for a moment, a failure is never remembered as an answer, indexing is not touched, and a Redis that cannot be reached only means every question goes to the provider.
 * The Redis tests use the Redis the rest of the suite uses (REDIS_HOST/REDIS_PORT), skip themselves where there is none, and remove their own keys.
 */
class CachingTextEmbedderTests {

    /** A provider that counts how often it is asked and can be told to fail. */
    private static final class FakeProvider implements TextEmbedder {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failing;
        final String model;

        FakeProvider(String model) {
            this.model = model;
        }

        @Override
        public String modelId() {
            return model;
        }

        @Override
        public double minSimilarity() {
            return 0.62;
        }

        @Override
        public Optional<float[]> embed(String text) {
            calls.incrementAndGet();
            if (failing) {
                return Optional.empty();
            }
            float[] v = new float[DIMENSIONS];
            v[Math.floorMod(text.hashCode(), DIMENSIONS)] = 1f;
            v[0] += 0.5f;
            return Optional.of(v);
        }
    }

    private JedisConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private final List<String> keys = new ArrayList<>();
    /** A model id of its own per test, so a run cannot see another run's keys. */
    private final String model = "test:" + UUID.randomUUID();

    @BeforeEach
    void connect() {
        connectionFactory = new JedisConnectionFactory(new RedisStandaloneConfiguration(System.getenv().getOrDefault("REDIS_HOST", "localhost"),
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"))));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterEach
    void cleanUp() {
        try {
            keys.forEach(redis::delete);
        } catch (RuntimeException ignored) {
            // no Redis in this run: there was nothing to remove
        }
        connectionFactory.destroy();
    }

    private void requireRedis() {
        try {
            Assumptions.assumeTrue("PONG".equals(connectionFactory.getConnection().ping()), "no Redis to test against");
        } catch (RuntimeException e) {
            Assumptions.abort("no Redis to test against: " + e.getMessage());
        }
    }

    private CachingTextEmbedder caching(FakeProvider provider, Duration cooldown) {
        return new CachingTextEmbedder(provider, redis, Duration.ofHours(1), cooldown, new SimpleMeterRegistry());
    }

    private CachingTextEmbedder remember(CachingTextEmbedder embedder, String text) {
        keys.add(embedder.cacheKey(text));
        return embedder;
    }

    @Test
    void aRepeatedQuestionIsAnsweredFromRedisAndNotFromTheProviderEvenAfterARestart() {
        requireRedis();
        var provider = new FakeProvider(model);
        var first = remember(caching(provider, Duration.ofSeconds(30)), "What does the policy say about attendance?");

        var fresh = first.embedQuery("What does the policy say about attendance?");
        assertThat(provider.calls).hasValue(1);

        var afterRestart = caching(provider, Duration.ofSeconds(30));   // a new process with nothing of its own
        var again = afterRestart.embedQuery("What does the policy say about attendance?");

        assertThat(provider.calls).as("not asked again").hasValue(1);
        assertThat(again).isPresent();
        assertThat(again.get()).containsExactly(fresh.get());
        assertThat(redis.getExpire(first.cacheKey("What does the policy say about attendance?"))).as("it expires on its own").isPositive();
    }

    @Test
    void whitespaceDoesNotMakeADifferentQuestionButWordsAndCaseDo() {
        requireRedis();
        var provider = new FakeProvider(model);
        var embedder = caching(provider, Duration.ofSeconds(30));
        remember(embedder, "late fee rules");
        remember(embedder, "Late fee rules");

        embedder.embedQuery("late fee rules");
        embedder.embedQuery("  late   fee\trules \n");
        assertThat(provider.calls).as("the same words, tidied").hasValue(1);

        embedder.embedQuery("Late fee rules");
        assertThat(provider.calls).as("the case is part of the text the provider sees").hasValue(2);
    }

    @Test
    void theModelIsPartOfTheKeySoAnotherModelNeverGetsAVectorFromTheOldSpace() {
        requireRedis();
        var providerA = new FakeProvider(model + ":a");
        var providerB = new FakeProvider(model + ":b");
        var a = remember(caching(providerA, Duration.ofSeconds(30)), "refund policy");
        var b = remember(caching(providerB, Duration.ofSeconds(30)), "refund policy");

        a.embedQuery("refund policy");
        b.embedQuery("refund policy");

        assertThat(a.cacheKey("refund policy")).isNotEqualTo(b.cacheKey("refund policy"));
        assertThat(providerB.calls).as("the second model was asked, not given the first one's vector").hasValue(1);
    }

    @Test
    void theQuestionItselfIsNeverStoredOnlyAHashOfIt() {
        requireRedis();
        var embedder = caching(new FakeProvider(model), Duration.ofSeconds(30));
        remember(embedder, "my private question about my own fees");

        embedder.embedQuery("my private question about my own fees");
        String key = embedder.cacheKey("my private question about my own fees");

        assertThat(key).startsWith(CachingTextEmbedder.KEY_PREFIX + model + ":").doesNotContain("private").doesNotContain("fees");
        assertThat(key.substring(key.lastIndexOf(':') + 1)).matches("[0-9a-f]{64}");
    }

    @Test
    void aFailureIsNeverRememberedAsAnAnswerAndTheProviderIsTriedAgainOnceItsRestIsOver() throws Exception {
        requireRedis();
        var provider = new FakeProvider(model);
        provider.failing = true;
        var embedder = remember(caching(provider, Duration.ofMillis(150)), "attendance policy");

        assertThat(embedder.embedQuery("attendance policy")).isEmpty();
        assertThat(redis.hasKey(embedder.cacheKey("attendance policy"))).as("nothing cached for a failure").isFalse();

        provider.failing = false;
        Thread.sleep(250);

        assertThat(embedder.embedQuery("attendance policy")).as("tried again after the rest").isPresent();
        assertThat(redis.hasKey(embedder.cacheKey("attendance policy"))).isTrue();
    }

    @Test
    void aProviderThatJustFailedIsNotAskedAgainForAWhileSoNoOneWaitsOutItsTimeoutInTurn() {
        requireRedis();
        var provider = new FakeProvider(model);
        provider.failing = true;
        var embedder = remember(caching(provider, Duration.ofSeconds(30)), "first question");
        remember(embedder, "second question");
        remember(embedder, "third question");

        embedder.embedQuery("first question");     // asks, fails
        embedder.embedQuery("second question");    // not asked: full-text answers at once
        embedder.embedQuery("third question");

        assertThat(provider.calls).hasValue(1);
    }

    @Test
    void aQuestionAlreadyInTheCacheIsStillAnsweredDuringTheRest() {
        requireRedis();
        var provider = new FakeProvider(model);
        var embedder = remember(caching(provider, Duration.ofSeconds(30)), "known question");
        remember(embedder, "new question");
        embedder.embedQuery("known question");
        provider.failing = true;
        embedder.embedQuery("new question");       // fails, starts the rest

        assertThat(embedder.embedQuery("known question")).as("from Redis, no provider needed").isPresent();
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    void indexingGoesStraightToTheProviderEveryTimeAndIsNeverHeldBackByTheRest() {
        var provider = new FakeProvider(model);
        var embedder = new CachingTextEmbedder(provider, null, Duration.ofHours(1), Duration.ofSeconds(30), null);
        provider.failing = true;
        embedder.embedQuery("a question");         // fails: the rest starts

        provider.failing = false;
        var passage = embedder.embed("a passage of a policy document");
        embedder.embed("a passage of a policy document");

        assertThat(passage).isPresent();
        assertThat(provider.calls).as("the question once, the passage twice, nothing held back").hasValue(3);
    }

    @Test
    void anEntryThatIsNotAVectorIsTreatedAsAbsentAndReplaced() {
        requireRedis();
        var provider = new FakeProvider(model);
        var embedder = remember(caching(provider, Duration.ofSeconds(30)), "damaged entry");
        redis.opsForValue().set(embedder.cacheKey("damaged entry"), "bm90IGEgdmVjdG9y", Duration.ofMinutes(5));

        assertThat(embedder.embedQuery("damaged entry")).isPresent();

        assertThat(provider.calls).hasValue(1);
        assertThat(embedder.embedQuery("damaged entry")).isPresent();
        assertThat(provider.calls).as("now cached properly").hasValue(1);
    }

    @Test
    void whenRedisCannotBeReachedEveryQuestionJustGoesToTheProvider() {
        var broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));
        var provider = new FakeProvider(model);
        var embedder = new CachingTextEmbedder(provider, broken, Duration.ofHours(1), Duration.ofSeconds(30), null);

        assertThat(embedder.embedQuery("one question")).isPresent();
        assertThat(embedder.embedQuery("one question")).isPresent();

        assertThat(provider.calls).as("no cache to help, and no failure either").hasValue(2);
    }

    @Test
    void aBlankQuestionNeverReachesTheProvider() {
        var provider = new FakeProvider(model);
        var embedder = new CachingTextEmbedder(provider, null, Duration.ofHours(1), Duration.ofSeconds(30), null);

        assertThat(embedder.embedQuery("   ")).isEmpty();
        assertThat(embedder.embedQuery(null)).isEmpty();
        assertThat(provider.calls).hasValue(0);
    }

    @Test
    void itCountsWhatHappenedSoAnOperatorCanSeeTheCacheWorking() {
        requireRedis();
        var meters = new SimpleMeterRegistry();
        var provider = new FakeProvider(model);
        var embedder = new CachingTextEmbedder(provider, redis, Duration.ofHours(1), Duration.ofSeconds(30), meters);
        remember(embedder, "counted question");

        embedder.embedQuery("counted question");
        embedder.embedQuery("counted question");

        assertThat(meters.counter("setu.ai.embedding.query", "result", "miss").count()).isEqualTo(1.0);
        assertThat(meters.counter("setu.ai.embedding.query", "result", "hit").count()).isEqualTo(1.0);
    }
}
