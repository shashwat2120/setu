package com.setu.shared.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An embedder never throws and never makes its caller wait for a provider that is slow to fail (a quota error retried inside a client library, a
 * hung connection): the AI advisor's policy-search tool sits inside a chat answer with a time budget of its own, and a slow embedding used to be
 * able to spend all of it. Past the timeout the answer is "unavailable", and search falls back to full-text.
 */
class SpringAiTextEmbedderTests {

    private static float[] vector(float value) {
        float[] v = new float[TextEmbedder.DIMENSIONS];
        java.util.Arrays.fill(v, value);
        return v;
    }

    @Test
    void aSlowProviderIsGivenUpOnAtTheTimeoutNotWaitedFor() {
        EmbeddingModel slow = mock(EmbeddingModel.class);
        when(slow.embed(anyString())).thenAnswer(call -> {
            Thread.sleep(5_000);
            return vector(1f);
        });
        var embedder = new SpringAiTextEmbedder(slow, "gemini:test", 0.6, Duration.ofMillis(300));

        long started = System.nanoTime();
        var result = embedder.embed("how many classes can I miss");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result).isEmpty();
        assertThat(elapsedMillis).as("returned at the timeout, not when the provider finally answered").isLessThan(2_000);
    }

    @Test
    void aFailingProviderIsEmptyNotAnException() {
        EmbeddingModel failing = mock(EmbeddingModel.class);
        when(failing.embed(anyString())).thenThrow(new IllegalStateException("429 You exceeded your current quota"));

        assertThat(new SpringAiTextEmbedder(failing, "gemini:test", 0.6, Duration.ofSeconds(2)).embed("anything")).isEmpty();
    }

    @Test
    void aGoodAnswerIsReturnedNormalised() {
        EmbeddingModel good = mock(EmbeddingModel.class);
        when(good.embed(anyString())).thenReturn(vector(3f));

        var unit = new SpringAiTextEmbedder(good, "gemini:test", 0.6, Duration.ofSeconds(2)).embed("anything").orElseThrow();

        double norm = 0;
        for (float v : unit) {
            norm += (double) v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void aVectorOfTheWrongSizeOrAllZeroOrNoTextIsEmpty() {
        EmbeddingModel odd = mock(EmbeddingModel.class);
        when(odd.embed("short")).thenReturn(new float[] {1f, 2f});
        when(odd.embed("zero")).thenReturn(new float[TextEmbedder.DIMENSIONS]);
        var embedder = new SpringAiTextEmbedder(odd, "gemini:test", 0.6, Duration.ofSeconds(2));

        assertThat(embedder.embed("short")).isEmpty();
        assertThat(embedder.embed("zero")).isEmpty();
        assertThat(embedder.embed("   ")).isEmpty();
        assertThat(embedder.embed(null)).isEmpty();
    }
}
