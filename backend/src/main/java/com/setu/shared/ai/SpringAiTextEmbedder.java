package com.setu.shared.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Optional;

/**
 * Meaning-level embeddings from a model provider through Spring AI: Gemini ({@code gemini-embedding-001}) or a local Ollama model. Never
 * throws: an unreachable provider, a quota error, a missing model or a vector of the wrong size gives an empty result, and policy search
 * falls back to full-text for that request (indexing leaves the chunk un-embedded and retries on the next pass).
 * <p>
 * <b>Bounded in time.</b> A provider that is slow to fail (a quota error that is retried inside the client library, a hung connection) used to
 * hold the caller for as long as it liked, and the caller is often the AI advisor's policy-search tool, whose whole answer has a time budget.
 * The call is given {@code timeout} (default 8 seconds, {@code app.ai.embedding.timeout-seconds}); past that it counts as unavailable, exactly like
 * any other failure, and search answers from full-text.
 * <p>
 * Vectors from different models cannot be compared, so there is deliberately no automatic switch from one embedding model to another
 * when a provider fails: full-text search is the fallback that is always consistent with what is stored.
 */
public class SpringAiTextEmbedder implements TextEmbedder {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTextEmbedder.class);

    private static final java.time.Duration DEFAULT_TIMEOUT = java.time.Duration.ofSeconds(8);

    private final EmbeddingModel embeddingModel;
    private final String modelId;
    private final double minSimilarity;
    private final java.time.Duration timeout;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean warnedAboutSize;

    public SpringAiTextEmbedder(EmbeddingModel embeddingModel, String modelId, double minSimilarity) {
        this(embeddingModel, modelId, minSimilarity, DEFAULT_TIMEOUT);
    }

    public SpringAiTextEmbedder(EmbeddingModel embeddingModel, String modelId, double minSimilarity, java.time.Duration timeout) {
        this.embeddingModel = embeddingModel;
        this.modelId = modelId;
        this.minSimilarity = minSimilarity;
        this.timeout = timeout;
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
            float[] vector = embedWithinTimeout(text);
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

    private float[] embedWithinTimeout(String text) {
        java.util.concurrent.Future<float[]> call = executor.submit(() -> embeddingModel.embed(text));
        try {
            return call.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            call.cancel(true);
            throw new IllegalStateException("no answer within " + timeout.toSeconds() + " s");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        }
    }
}
