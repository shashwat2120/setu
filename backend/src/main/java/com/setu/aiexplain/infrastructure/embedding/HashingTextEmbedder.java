package com.setu.aiexplain.infrastructure.embedding;

import com.setu.shared.ai.TextEmbedder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The built-in embedder: feature hashing of words and word pairs into a fixed-size vector, normalised to unit length. It needs no model,
 * no network and no download, is deterministic (so tests and re-indexing are reproducible), and is the default.
 * <p>
 * Honest scope: this captures <i>lexical</i> similarity (shared words, with plurals and common endings folded together), not meaning.
 * "attendance requirement" finds "attendance policy"; "absence rules" does not find it. For meaning-level retrieval switch
 * {@code app.ai.embedding.provider} to {@code ollama} with a 768-dimension model such as {@code nomic-embed-text}; the pgvector storage,
 * ranking and access control are identical either way.
 */
public class HashingTextEmbedder implements TextEmbedder {

    public static final String MODEL_ID = "local-hash-v1-768";

    private static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have", "how", "in",
        "is", "it", "its", "of", "on", "or", "that", "the", "their", "this", "to", "was", "what", "when", "which", "who", "will", "with", "do",
        "does", "can", "my", "me", "i", "we", "you", "your");

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    /** Hashed word vectors of unrelated text sit near zero; one shared word in a short passage scores about 0.15. */
    @Override
    public double minSimilarity() {
        return 0.12;
    }

    @Override
    public Optional<float[]> embed(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] words = tokens(text);
        if (words.length == 0) {
            return Optional.empty();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < words.length; i++) {
            counts.merge(words[i], 1, Integer::sum);
            if (i + 1 < words.length) {
                counts.merge(words[i] + " " + words[i + 1], 1, Integer::sum);
            }
        }
        float[] vector = new float[DIMENSIONS];
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int hash = fnv1a(e.getKey());
            int slot = Math.floorMod(hash, DIMENSIONS);
            float sign = ((hash >>> 31) & 1) == 0 ? 1f : -1f;
            float weight = (e.getKey().indexOf(' ') >= 0 ? 0.5f : 1f) * (1f + (float) Math.log(e.getValue()));
            vector[slot] += sign * weight;
        }
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        if (norm == 0) {
            return Optional.empty();
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return Optional.of(vector);
    }

    static String[] tokens(String text) {
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(w -> w.length() >= 2 && !STOP_WORDS.contains(w))
            .map(HashingTextEmbedder::stem)
            .toArray(String[]::new);
    }

    /** Folds the commonest English endings so "grades", "graded" and "grading" land on the same feature. */
    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) {
            return w.substring(0, w.length() - 3);
        }
        if (w.length() > 4 && w.endsWith("ed")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 4 && w.endsWith("es")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static int fnv1a(String s) {
        int hash = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash;
    }
}
