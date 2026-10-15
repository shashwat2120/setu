package com.setu.aiexplain.infrastructure.ai;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Spring AI 2.x refuses to create its Google GenAI client without credentials, which used to make the WHOLE backend
 * fail at startup whenever {@code AI_PROVIDER=google-genai} was selected but no {@code GEMINI_API_KEY} was set — the
 * opposite of this app's stated rule that a Gemini problem must never take the AI Advisor down (Ollama is the
 * always-available fallback). If Gemini is selected without any credentials, this switches the primary provider to
 * Ollama for this run and says so loudly, instead of aborting the application.
 * <p>
 * Runs last so it sees the final value of every property, including those loaded from {@code .env.local} and test
 * overrides. Only the missing-credentials case is handled; a present-but-wrong key still starts normally and fails
 * (and falls back) at request time, which {@code AiChatService} already handles.
 */
public class AiProviderSafetyPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROVIDER_PROPERTY = "spring.ai.model.chat";
    static final String GENAI = "google-genai";

    private final Log log;

    public AiProviderSafetyPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(AiProviderSafetyPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        applyChatFallback(environment);
        selectEmbeddingProvider(environment);
    }

    /**
     * Policy search embeddings follow the chat provider unless told otherwise ({@code app.ai.embedding.provider}: auto, gemini, ollama,
     * local): Gemini when Gemini is the (still selected) chat provider, otherwise the built-in local embedder. Only the chosen provider's
     * Spring AI model is created ({@code spring.ai.model.embedding}), so an unused provider can never fail startup, for example Gemini
     * without a key.
     */
    private void selectEmbeddingProvider(ConfigurableEnvironment environment) {
        String requested = environment.getProperty("app.ai.embedding.provider", "auto").trim().toLowerCase();
        String chat = environment.getProperty(PROVIDER_PROPERTY, "ollama");
        boolean geminiUsable = StringUtils.hasText(environment.getProperty("spring.ai.google.genai.api-key"));
        String resolved = switch (requested) {
            case "gemini" -> geminiUsable ? "gemini" : "local";
            case "ollama" -> "ollama";
            case "local" -> "local";
            default -> GENAI.equals(chat) && geminiUsable ? "gemini" : "local";
        };
        // Spring AI switches the two providers with different keys: Gemini's text-embedding model on "spring.ai.model.embedding.text",
        // Ollama's on "spring.ai.model.embedding". Each is set so that only the chosen provider's model is created.
        environment.getPropertySources().addFirst(new MapPropertySource("setuEmbeddingProvider", Map.of(
            "app.ai.embedding.resolved", resolved,
            "spring.ai.model.embedding.text", resolved.equals("gemini") ? GENAI : "none",
            "spring.ai.model.embedding", resolved.equals("ollama") ? "ollama" : "none")));
    }

    private void applyChatFallback(ConfigurableEnvironment environment) {
        if (!GENAI.equals(environment.getProperty(PROVIDER_PROPERTY))) {
            return;
        }
        boolean hasApiKey = StringUtils.hasText(environment.getProperty("spring.ai.google.genai.api-key"));
        boolean hasVertex = StringUtils.hasText(environment.getProperty("spring.ai.google.genai.project-id"))
            && StringUtils.hasText(environment.getProperty("spring.ai.google.genai.location"));
        if (hasApiKey || hasVertex) {
            return;
        }
        log.warn("AI_PROVIDER=" + GENAI + " but no GEMINI_API_KEY (or Vertex project/location) is configured — "
            + "starting with Ollama as the primary AI provider instead of failing. Set GEMINI_API_KEY to use Gemini.");
        environment.getPropertySources().addFirst(
            new MapPropertySource("setuAiProviderFallback", Map.of(PROVIDER_PROPERTY, "ollama")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
