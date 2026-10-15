package com.setu.shared.ai;

import java.util.Optional;

/**
 * Turns text into a fixed-size vector for similarity search. A cross-module contract: ai-explain implements it (it owns every model
 * provider), institution uses it to index and search policy documents.
 * <p>
 * {@link #modelId()} identifies the embedding space. Vectors from different models are not comparable, so callers store the id beside
 * each vector and only ever compare vectors that share it. {@link #embed} returns empty, never throws, when no vector can be produced
 * (the provider is down, or the text has nothing to embed); callers then fall back to full-text search.
 */
public interface TextEmbedder {

    /** Length of every vector this contract produces; matches the {@code VECTOR(768)} column of {@code institution.policy_chunks}. */
    int DIMENSIONS = 768;

    String modelId();

    /** Cosine similarity below which a vector match is treated as unrelated; depends on how the model spreads its scores. */
    double minSimilarity();

    /** A unit-length vector of {@link #DIMENSIONS} values, or empty. */
    Optional<float[]> embed(String text);
}
