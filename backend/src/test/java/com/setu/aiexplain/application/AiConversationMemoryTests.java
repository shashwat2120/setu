package com.setu.aiexplain.application;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The bounds and isolation guarantees {@link AiConversationMemory} documents — the model only ever sees what this returns. */
class AiConversationMemoryTests {

    private static AiConversationMemory.Key key(UUID user, UUID tenant) {
        return AiConversationMemory.Key.of(user, Optional.of(tenant));
    }

    @Test
    void remembersCompletedExchangesOldestFirst() {
        var memory = new AiConversationMemory(6, 30, 100);
        var k = key(UUID.randomUUID(), UUID.randomUUID());

        memory.append(k, "q1", "a1");
        memory.append(k, "q2", "a2");

        assertThat(memory.history(k)).containsExactly(
            new AiConversationMemory.Turn("q1", "a1"), new AiConversationMemory.Turn("q2", "a2"));
    }

    @Test
    void keepsOnlyTheMostRecentExchanges() {
        var memory = new AiConversationMemory(2, 30, 100);
        var k = key(UUID.randomUUID(), UUID.randomUUID());

        memory.append(k, "q1", "a1");
        memory.append(k, "q2", "a2");
        memory.append(k, "q3", "a3");

        assertThat(memory.history(k)).extracting(AiConversationMemory.Turn::user).containsExactly("q2", "q3");
    }

    @Test
    void conversationsAreIsolatedPerUserAndPerTenant() {
        var memory = new AiConversationMemory(6, 30, 100);
        UUID user = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        memory.append(key(user, tenantA), "mine", "reply");

        assertThat(memory.history(key(UUID.randomUUID(), tenantA))).isEmpty();
        assertThat(memory.history(key(user, tenantB))).isEmpty();
        assertThat(memory.history(key(user, tenantA))).hasSize(1);
    }

    @Test
    void clearForgetsTheConversation() {
        var memory = new AiConversationMemory(6, 30, 100);
        var k = key(UUID.randomUUID(), UUID.randomUUID());
        memory.append(k, "q", "a");

        memory.clear(k);

        assertThat(memory.history(k)).isEmpty();
    }

    @Test
    void idleConversationExpires() {
        var memory = new AiConversationMemory(6, 0, 100); // zero-minute idle window: expired as soon as any time passes
        var k = key(UUID.randomUUID(), UUID.randomUUID());
        memory.append(k, "q", "a");

        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(memory.history(k)).isEmpty();
    }

    @Test
    void totalConversationsAreCapped() {
        var memory = new AiConversationMemory(6, 30, 3);
        UUID tenant = UUID.randomUUID();
        var keys = new java.util.ArrayList<AiConversationMemory.Key>();
        for (int i = 0; i < 6; i++) {
            var k = key(UUID.randomUUID(), tenant);
            keys.add(k);
            memory.append(k, "q" + i, "a" + i);
        }

        long held = keys.stream().filter(k -> !memory.history(k).isEmpty()).count();
        assertThat(held).isLessThanOrEqualTo(3);
        assertThat(memory.history(keys.get(5))).hasSize(1); // the newest is never the one evicted
    }

    @Test
    void zeroMaxExchangesDisablesMemory() {
        var memory = new AiConversationMemory(0, 30, 100);
        var k = key(UUID.randomUUID(), UUID.randomUUID());

        memory.append(k, "q", "a");

        assertThat(memory.history(k)).isEmpty();
    }

    @Test
    void storedTextIsCapped() {
        var memory = new AiConversationMemory(6, 30, 100);
        var k = key(UUID.randomUUID(), UUID.randomUUID());

        memory.append(k, "x".repeat(10_000), "y".repeat(10_000));

        var turn = memory.history(k).get(0);
        assertThat(turn.user()).hasSize(4000);
        assertThat(turn.assistant()).hasSize(4000);
    }
}
