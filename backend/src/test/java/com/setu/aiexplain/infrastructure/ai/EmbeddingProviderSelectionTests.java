package com.setu.aiexplain.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy-search embeddings follow the chat provider: Gemini when Gemini is the primary (and has a key), the built-in local embedder
 * otherwise, with explicit overrides. Only the chosen provider's Spring AI model is ever created, so an unused one cannot fail startup.
 */
class EmbeddingProviderSelectionTests {

    private final AiProviderSafetyPostProcessor processor = new AiProviderSafetyPostProcessor((DeferredLogFactory) supplier -> supplier.get());

    private MockEnvironment run(MockEnvironment env) {
        processor.postProcessEnvironment(env, null);
        return env;
    }

    @Test
    void geminiChatWithAKeyUsesGeminiEmbeddings() {
        var env = run(new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai").withProperty("spring.ai.google.genai.api-key", "k"));

        assertThat(env.getProperty("app.ai.embedding.resolved")).isEqualTo("gemini");
        assertThat(env.getProperty("spring.ai.model.embedding.text")).isEqualTo("google-genai");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
    }

    @Test
    void theGeminiEmbeddingAutoConfigurationsAreExcludedUnlessGeminiEmbeddingsAreSelected() {
        var local = run(new MockEnvironment().withProperty("spring.ai.model.chat", "ollama"));
        assertThat(local.getProperty("spring.autoconfigure.exclude")).contains("GoogleGenAiEmbeddingConnectionAutoConfiguration")
            .contains("GoogleGenAiTextEmbeddingAutoConfiguration");

        var keptExisting = run(new MockEnvironment().withProperty("spring.ai.model.chat", "ollama").withProperty("spring.autoconfigure.exclude", "com.example.Other"));
        assertThat(keptExisting.getProperty("spring.autoconfigure.exclude")).contains("com.example.Other").contains("GoogleGenAiEmbeddingConnectionAutoConfiguration");

        var gemini = run(new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai").withProperty("spring.ai.google.genai.api-key", "k"));
        assertThat(gemini.getProperty("spring.autoconfigure.exclude")).isNull();
    }

    @Test
    void geminiChatWithoutAKeyFallsBackToLocalEverywhere() {
        var env = run(new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai").withProperty("spring.ai.google.genai.api-key", ""));

        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("ollama");
        assertThat(env.getProperty("app.ai.embedding.resolved")).isEqualTo("local");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.embedding.text")).isEqualTo("none");
    }

    @Test
    void ollamaChatUsesTheLocalEmbedderUnlessAskedOtherwise() {
        assertThat(run(new MockEnvironment().withProperty("spring.ai.model.chat", "ollama")).getProperty("app.ai.embedding.resolved")).isEqualTo("local");

        var env = run(new MockEnvironment().withProperty("spring.ai.model.chat", "ollama").withProperty("app.ai.embedding.provider", "ollama"));
        assertThat(env.getProperty("app.ai.embedding.resolved")).isEqualTo("ollama");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("ollama");
        assertThat(env.getProperty("spring.ai.model.embedding.text")).isEqualTo("none");
    }

    @Test
    void anExplicitLocalChoiceWinsEvenWithGeminiAvailable() {
        var env = run(new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai").withProperty("spring.ai.google.genai.api-key", "k")
            .withProperty("app.ai.embedding.provider", "local"));

        assertThat(env.getProperty("app.ai.embedding.resolved")).isEqualTo("local");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
    }

    @Test
    void askingForGeminiWithoutAKeyIsNotFatal() {
        var env = run(new MockEnvironment().withProperty("spring.ai.model.chat", "ollama").withProperty("app.ai.embedding.provider", "gemini"));

        assertThat(env.getProperty("app.ai.embedding.resolved")).isEqualTo("local");
    }
}
