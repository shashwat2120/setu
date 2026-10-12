package com.setu.aiexplain.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderSafetyPostProcessorTests {

    private final DeferredLogFactory logFactory = supplier -> supplier.get();
    private final AiProviderSafetyPostProcessor processor = new AiProviderSafetyPostProcessor(logFactory);

    private String providerAfter(MockEnvironment env) {
        processor.postProcessEnvironment(env, null);
        return env.getProperty("spring.ai.model.chat");
    }

    @Test
    void geminiWithoutAnyCredentialsFallsBackToOllamaInsteadOfFailingStartup() {
        var env = new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai")
            .withProperty("spring.ai.google.genai.api-key", "");

        assertThat(providerAfter(env)).isEqualTo("ollama");
    }

    @Test
    void geminiWithAnApiKeyIsLeftAlone() {
        var env = new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai")
            .withProperty("spring.ai.google.genai.api-key", "some-key");

        assertThat(providerAfter(env)).isEqualTo("google-genai");
    }

    @Test
    void geminiViaVertexProjectAndLocationIsLeftAlone() {
        var env = new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai")
            .withProperty("spring.ai.google.genai.project-id", "p").withProperty("spring.ai.google.genai.location", "us-central1");

        assertThat(providerAfter(env)).isEqualTo("google-genai");
    }

    @Test
    void ollamaPrimaryIsLeftAlone() {
        var env = new MockEnvironment().withProperty("spring.ai.model.chat", "ollama");

        assertThat(providerAfter(env)).isEqualTo("ollama");
    }
}
