package com.setu.aiexplain.application;

import org.springframework.beans.factory.annotation.Value;
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
 *   <li>Bounded three ways: a few exchanges per conversation, an idle expiry, and a cap on how many
 *       conversations are held at once. In-process by design — losing it on restart only means starting a
 *       fresh conversation, which is also what "New conversation" does.</li>
 * </ul>
 */
@Component
class AiConversationMemory {

    private static final UUID NO_TENANT = new UUID(0L, 0L);
    private static final int MAX_STORED_TEXT = 4000;

    record Key(UUID userId, UUID tenantId) {
        static Key of(UUID userId, java.util.Optional<UUID> tenantId) {
            return new Key(userId, tenantId.orElse(NO_TENANT));
        }
    }

    record Turn(String user, String assistant) {
    }

    private static final class Conversation {
        final Deque<Turn> turns = new ArrayDeque<>();
        volatile Instant lastUsed = Instant.now();
    }

    private final Map<Key, Conversation> conversations = new ConcurrentHashMap<>();
    private final int maxExchanges;
    private final Duration idleTtl;
    private final int maxConversations;

    AiConversationMemory(@Value("${app.ai.memory.max-exchanges:6}") int maxExchanges,
                          @Value("${app.ai.memory.idle-minutes:30}") long idleMinutes,
                          @Value("${app.ai.memory.max-conversations:2000}") int maxConversations) {
        this.maxExchanges = maxExchanges;
        this.idleTtl = Duration.ofMinutes(idleMinutes);
        this.maxConversations = maxConversations;
    }

    /** Oldest-first completed exchanges, or empty if none or the conversation has gone idle. */
    List<Turn> history(Key key) {
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

    void append(Key key, String userText, String assistantText) {
        if (maxExchanges <= 0) {
            return;
        }
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

    void clear(Key key) {
        conversations.remove(key);
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
