package com.setu.shared.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Chooses the one embedder policy search uses. Which provider is decided early, in {@code AiProviderSafetyPostProcessor}, so the
 * matching Spring AI model is the only one created: {@code gemini} when Gemini is the chat provider (the default whenever it is
 * available, matching "Gemini first"), {@code ollama} when asked for, otherwise {@code local}, the built-in lexical embedder that needs
 * nothing. One embedder at a time, because its model id decides which stored vectors a query can use.
 */
@Configuration
class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    /** Gemini scores related passages about 0.68 to 0.80 and unrelated text 0.40 to 0.54 (measured on this project's policy text). */
    static final double GEMINI_MIN_SIMILARITY = 0.62;

    @Bean
    TextEmbedder textEmbedder(@Value("${app.ai.embedding.resolved:local}") String provider,
                               @Value("${spring.ai.google.genai.embedding.text.options.model:gemini-embedding-001}") String geminiModel,
                               @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}") String ollamaModel,
                               @Value("${app.ai.embedding.timeout-seconds:8}") long timeoutSeconds,
                               ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        List<EmbeddingModel> embeddingModels = embeddingModelProvider.orderedStream().toList();
        switch (provider) {
            case "gemini" -> {
                EmbeddingModel model = find(embeddingModels, "GoogleGenAi");
                if (model != null) {
                    log.info("policy search embeddings: gemini:{} (full-text search if Gemini is unavailable)", geminiModel);
                    return new SpringAiTextEmbedder(model, "gemini:" + geminiModel, GEMINI_MIN_SIMILARITY, java.time.Duration.ofSeconds(timeoutSeconds));
                }
                log.warn("Gemini embeddings were selected but no Gemini embedding model was created; using the built-in local embedder");
            }
            case "ollama" -> {
                EmbeddingModel model = find(embeddingModels, "Ollama");
                if (model != null) {
                    log.info("policy search embeddings: ollama:{}", ollamaModel);
                    return new SpringAiTextEmbedder(model, "ollama:" + ollamaModel, 0.45, java.time.Duration.ofSeconds(timeoutSeconds));
                }
                log.warn("Ollama embeddings were selected but no Ollama embedding model was created; using the built-in local embedder");
            }
            default -> {
            }
        }
        log.info("policy search embeddings: {}", HashingTextEmbedder.MODEL_ID);
        return new HashingTextEmbedder();
    }

    private static EmbeddingModel find(List<EmbeddingModel> models, String classNamePart) {
        return models.stream().filter(m -> m.getClass().getSimpleName().contains(classNamePart)).findFirst().orElse(null);
    }
}
