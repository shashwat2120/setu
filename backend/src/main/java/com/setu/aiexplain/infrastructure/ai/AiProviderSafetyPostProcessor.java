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
