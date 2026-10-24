package com.setu.aiexplain.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-term conversation memory so a follow-up ("and what about CS201?") has the context of the question
 * before it. Replaces the earlier "no memory of any kind" rule (a Day 14 code-level instruction, not part of
 * the locked spec) with a deliberately narrow version:
 * <ul>
 *   <li>Held on the SERVER, keyed by (user, tenant) taken from the authenticated principal — never sent
 *       back and forth by the client, so a caller cannot forge earlier "assistant" turns, and one user's
 *       history can never be read by another.</li>
 *   <li>Only the plain text of completed question/answer pairs is kept. Tool arguments and raw tool results
 *       never enter memory, so no per-student record sits there beyond what the model already said.</li>
 *   <li>Bounded: a few exchanges per conversation, a cap on the text of each, and an idle expiry.</li>
 * </ul>
 * <b>Where it is kept.</b> In Redis (a list per conversation, {@code setu:ai:memory:<tenant>:<user>}, trimmed to the last few exchanges and expiring after the idle
 * time), so a conversation survives a restart of the advisor service and is the same whichever instance answers the next question. Redis is never the source of
 * truth here, as everywhere in this application: if it cannot be reached the memory falls back to this process's own map for that call, logged, so the advisor
 * keeps working with the short, per-instance memory it used to have. {@code app.ai.memory.store=local} keeps it in-process only, and the constructors used by
 * tests do the same. What is stored in Redis is the same plain question and answer text the model was already shown, under a key made only of the caller's own ids.
 */
@Component
class AiConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(AiConversationMemory.class);

    private static final UUID NO_TENANT = new UUID(0L, 0L);
    private static final int MAX_STORED_TEXT = 4000;
    static final String KEY_PREFIX = "setu:ai:memory:";
    /** How often a Redis problem is written to the log: one line per minute, not one per question. */
    private static final Duration LOG_EVERY = Duration.ofMinutes(1);

    record Key(UUID userId, UUID tenantId) {
        static Key of(UUID userId, java.util.Optional<UUID> tenantId) {
            return new Key(userId, tenantId.orElse(NO_TENANT));
        }

        String redisKey() {
            return KEY_PREFIX + tenantId + ":" + userId;
        }
    }

    record Turn(String user, String assistant) {
    }

    /** One stored exchange, as it is written to Redis. */
    private record Entry(String u, String a) {
    }

    private static final class Conversation {
        final Deque<Turn> turns = new ArrayDeque<>();
        volatile Instant lastUsed = Instant.now();
    }

    private final Map<Key, Conversation> conversations = new ConcurrentHashMap<>();
    private final int maxExchanges;
    private final Duration idleTtl;
    private final int maxConversations;
    private final StringRedisTemplate redis;       // null: in-process only
    private final ObjectMapper objectMapper;
    private volatile Instant lastRedisLog = Instant.EPOCH;

    @Autowired
    AiConversationMemory(@Value("${app.ai.memory.max-exchanges:6}") int maxExchanges,
                          @Value("${app.ai.memory.idle-minutes:30}") long idleMinutes,
                          @Value("${app.ai.memory.max-conversations:2000}") int maxConversations,
                          @Value("${app.ai.memory.store:redis}") String store,
                          ObjectProvider<StringRedisTemplate> redis, ObjectProvider<ObjectMapper> objectMapper) {
        this(maxExchanges, idleMinutes, maxConversations, "redis".equalsIgnoreCase(store.trim()) ? redis.getIfAvailable() : null,
            objectMapper.getIfAvailable(ObjectMapper::new));
    }

    /** In-process only: how the unit tests build it, and what {@code app.ai.memory.store=local} gives. */
    AiConversationMemory(int maxExchanges, long idleMinutes, int maxConversations) {
        this(maxExchanges, idleMinutes, maxConversations, null, new ObjectMapper());
    }

    AiConversationMemory(int maxExchanges, long idleMinutes, int maxConversations, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.maxExchanges = maxExchanges;
        this.idleTtl = Duration.ofMinutes(idleMinutes);
        this.maxConversations = maxConversations;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** Oldest-first completed exchanges, or empty if none or the conversation has gone idle. */
    List<Turn> history(Key key) {
        if (redis != null) {
            try {
                List<String> raw = redis.opsForList().range(key.redisKey(), 0, -1);
                List<Turn> turns = new ArrayList<>();
                for (String json : raw == null ? List.<String>of() : raw) {
                    Turn turn = parse(json);
                    if (turn != null) {
                        turns.add(turn);
                    }
                }
                return turns;
            } catch (RuntimeException e) {
                redisUnavailable("read", e);
            }
        }
        return localHistory(key);
    }

    void append(Key key, String userText, String assistantText) {
        if (maxExchanges <= 0) {
            return;
        }
        if (redis != null) {
            try {
                String k = key.redisKey();
                redis.opsForList().rightPush(k, objectMapper.writeValueAsString(new Entry(cap(userText), cap(assistantText))));
                redis.opsForList().trim(k, -maxExchanges, -1);
                redis.expire(k, idleTtl.isZero() ? Duration.ofMillis(1) : idleTtl);
                return;
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                redisUnavailable("write", e);
            }
        }
        localAppend(key, userText, assistantText);
    }

    void clear(Key key) {
        if (redis != null) {
            try {
                redis.delete(key.redisKey());
            } catch (RuntimeException e) {
                redisUnavailable("clear", e);
            }
        }
        conversations.remove(key);   // whatever a Redis outage left in this process goes too
    }

    private Turn parse(String json) {
        try {
            Entry entry = objectMapper.readValue(json, Entry.class);
            return entry == null || entry.u() == null || entry.a() == null ? null : new Turn(entry.u(), entry.a());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
            return null;   // an element that is not ours to read is left out, never allowed to fail the question
        }
    }

    private void redisUnavailable(String what, Exception e) {
        Instant now = Instant.now();
        if (lastRedisLog.plus(LOG_EVERY).isBefore(now)) {
            lastRedisLog = now;
            log.warn("advisor conversation memory: Redis {} failed ({}); using this instance's own memory until it is back", what, e.toString());
        }
    }

    // ---- this process's own memory: the fallback, and the whole of it when Redis is not in use ----------------------------------------

    private List<Turn> localHistory(Key key) {
        Conversation c = conversations.get(key);
        if (c == null) {
            return List.of();
        }
        synchronized (c) {
            if (isExpired(c)) {
                conversations.remove(key, c);
                return List.of();
            }
            return new ArrayList<>(c.turns);
        }
    }

    private void localAppend(Key key, String userText, String assistantText) {
        evictIfNeeded();
        Conversation c = conversations.computeIfAbsent(key, k -> new Conversation());
        synchronized (c) {
            if (isExpired(c)) {
                c.turns.clear();
            }
            c.turns.addLast(new Turn(cap(userText), cap(assistantText)));
            while (c.turns.size() > maxExchanges) {
                c.turns.removeFirst();
            }
            c.lastUsed = Instant.now();
        }
    }

    private boolean isExpired(Conversation c) {
        return c.lastUsed.plus(idleTtl).isBefore(Instant.now());
    }

    private static String cap(String text) {
        return text.length() <= MAX_STORED_TEXT ? text : text.substring(0, MAX_STORED_TEXT);
    }

    private void evictIfNeeded() {
        if (conversations.size() < maxConversations) {
            return;
        }
        conversations.entrySet().removeIf(e -> isExpired(e.getValue()));
        if (conversations.size() >= maxConversations) {
            conversations.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().lastUsed))
                .ifPresent(e -> conversations.remove(e.getKey()));
        }
    }
}
