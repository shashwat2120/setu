package com.setu.aiexplain.infrastructure.embedding;

import com.setu.shared.ai.TextEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Optional;

/**
 * Meaning-level embeddings from a model provider through Spring AI: Gemini ({@code gemini-embedding-001}) or a local Ollama model. Never
 * throws: an unreachable provider, a quota error, a missing model or a vector of the wrong size gives an empty result, and policy search
 * falls back to full-text for that request (indexing leaves the chunk un-embedded and retries on the next pass).
 * <p>
 * Vectors from different models cannot be compared, so there is deliberately no automatic switch from one embedding model to another
 * when a provider fails: full-text search is the fallback that is always consistent with what is stored.
 */
public class SpringAiTextEmbedder implements TextEmbedder {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTextEmbedder.class);

    private final EmbeddingModel embeddingModel;
    private final String modelId;
    private final double minSimilarity;
    private volatile boolean warnedAboutSize;

    public SpringAiTextEmbedder(EmbeddingModel embeddingModel, String modelId, double minSimilarity) {
        this.embeddingModel = embeddingModel;
        this.modelId = modelId;
        this.minSimilarity = minSimilarity;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public double minSimilarity() {
        return minSimilarity;
    }

    @Override
    public Optional<float[]> embed(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            float[] vector = embeddingModel.embed(text);
            if (vector == null || vector.length != DIMENSIONS) {
                if (!warnedAboutSize) {
                    warnedAboutSize = true;
                    log.warn("Embedding model '{}' returned {} dimensions; policy search needs {}.", modelId, vector == null ? 0 : vector.length, DIMENSIONS);
                }
                return Optional.empty();
            }
            double norm = 0;
            for (float v : vector) {
                norm += (double) v * v;
            }
            if (norm == 0) {
                return Optional.empty();
            }
            float scale = (float) (1.0 / Math.sqrt(norm));
            float[] unit = new float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                unit[i] = vector[i] * scale;
            }
            return Optional.of(unit);
        } catch (RuntimeException e) {
            log.warn("Embedding with {} failed ({}); policy search falls back to full-text for this request", modelId, e.toString());
            return Optional.empty();
        }
    }
}
