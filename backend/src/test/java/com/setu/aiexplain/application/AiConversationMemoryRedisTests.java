package com.setu.aiexplain.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The advisor's conversation memory kept in Redis: it outlives the advisor process (a restart, or another instance answering the next question), it is still bounded and
 * isolated per user and tenant, and when Redis cannot be reached the advisor keeps its own short memory instead of failing. The Redis tests use the Redis the rest of the
 * test suite uses (REDIS_HOST/REDIS_PORT) and skip themselves where there is none; every key is made of random ids and removed afterwards.
 */
class AiConversationMemoryRedisTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JedisConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private AiConversationMemory.Key key;

    @BeforeEach
    void connect() {
        var config = new RedisStandaloneConfiguration(System.getenv().getOrDefault("REDIS_HOST", "localhost"),
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380")));
        connectionFactory = new JedisConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        key = AiConversationMemory.Key.of(user, Optional.of(tenant));
    }

    @AfterEach
    void cleanUp() {
        try {
            redis.delete(key.redisKey());
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

    private AiConversationMemory memory(int maxExchanges, long idleMinutes) {
        return new AiConversationMemory(maxExchanges, idleMinutes, 100, redis, MAPPER);
    }

    @Test
    void aConversationSurvivesARestartAndIsTheSameOnAnotherInstance() {
        requireRedis();
        memory(6, 30).append(key, "what is my attendance?", "It is **74%**.");
        memory(6, 30).append(key, "and CS201?", "CS201 is 80%.");

        var afterRestart = memory(6, 30);   // a new process, with nothing of its own

        assertThat(afterRestart.history(key)).containsExactly(
            new AiConversationMemory.Turn("what is my attendance?", "It is **74%**."),
            new AiConversationMemory.Turn("and CS201?", "CS201 is 80%."));
    }

    @Test
    void itIsKeyedByUserAndTenantOnlySoNobodyElseReadsIt() {
        requireRedis();
        var memory = memory(6, 30);
        memory.append(key, "mine", "reply");

        assertThat(memory.history(AiConversationMemory.Key.of(UUID.randomUUID(), Optional.of(tenant)))).as("another user, same college").isEmpty();
        assertThat(memory.history(AiConversationMemory.Key.of(user, Optional.of(UUID.randomUUID())))).as("same user, another college").isEmpty();
        assertThat(memory.history(key)).hasSize(1);
        assertThat(key.redisKey()).isEqualTo("setu:ai:memory:" + tenant + ":" + user);
    }

    @Test
    void itKeepsOnlyTheLastFewExchangesAndExpiresWhenIdle() {
        requireRedis();
        var memory = memory(2, 30);
        memory.append(key, "q1", "a1");
        memory.append(key, "q2", "a2");
        memory.append(key, "q3", "a3");

        assertThat(memory.history(key)).extracting(AiConversationMemory.Turn::user).containsExactly("q2", "q3");
        Long seconds = redis.getExpire(key.redisKey());
        assertThat(seconds).as("it expires on its own").isNotNull().isPositive().isLessThanOrEqualTo(Duration.ofMinutes(30).toSeconds());
    }

    @Test
    void anIdleConversationIsGoneOnceItsTimeIsUp() throws Exception {
        requireRedis();
        var memory = new AiConversationMemory(6, 0, 100, redis, MAPPER);   // a zero idle window: gone as soon as any time passes
        memory.append(key, "q", "a");
        Thread.sleep(50);

        assertThat(memory.history(key)).isEmpty();
    }

    @Test
    void newConversationClearsIt() {
        requireRedis();
        var memory = memory(6, 30);
        memory.append(key, "q", "a");

        memory.clear(key);

        assertThat(memory.history(key)).isEmpty();
        assertThat(redis.hasKey(key.redisKey())).isFalse();
    }

    @Test
    void textIsCappedAndAnElementThatIsNotOursIsLeftOutNotAllowedToFailTheQuestion() {
        requireRedis();
        var memory = memory(6, 30);
        memory.append(key, "x".repeat(10_000), "y".repeat(10_000));
        redis.opsForList().rightPush(key.redisKey(), "this is not json");
        redis.opsForList().rightPush(key.redisKey(), "{\"u\":\"only half\"}");

        var turns = memory.history(key);

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).user()).hasSize(4000);
        assertThat(turns.get(0).assistant()).hasSize(4000);
    }

    @Test
    void whenRedisCannotBeReachedTheAdvisorKeepsItsOwnMemoryInsteadOfFailing() {
        var broken = mock(StringRedisTemplate.class);
        when(broken.opsForList()).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(broken.delete(key.redisKey())).thenThrow(new RedisConnectionFailureException("connection refused"));
        var memory = new AiConversationMemory(6, 30, 100, broken, MAPPER);

        memory.append(key, "q1", "a1");   // does not throw
        memory.append(key, "q2", "a2");

        assertThat(memory.history(key)).containsExactly(new AiConversationMemory.Turn("q1", "a1"), new AiConversationMemory.Turn("q2", "a2"));
        memory.clear(key);                // does not throw, and clears what this process kept
        assertThat(memory.history(key)).isEmpty();
    }

    @Test
    void withoutRedisConfiguredItBehavesAsItAlwaysDid() {
        var local = new AiConversationMemory(6, 30, 100);
        local.append(key, "q", "a");

        assertThat(local.history(key)).containsExactly(new AiConversationMemory.Turn("q", "a"));
    }
}
