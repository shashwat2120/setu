package com.setu.shared.internalcall;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Reads an internal answer of the shape {@code {"results":[{"<idField>":"<id>", ...}, ...]}}: one entry per id asked about, each id once, nothing else. That shape is what lets
 * a caller tell "the owner has none" (an entry with an empty list or zero counts) from "the owner did not answer" (a missing entry), which is rejected as not an answer.
 */
public final class KeyedResults {

    private KeyedResults() {
    }

    /**
     * @param rowReader turns one entry into its value; may throw {@link IllegalArgumentException} (also from a bad UUID, number or time) for an entry that is not well formed
     * @throws IOException if the answer is malformed, incomplete, or names an id that was not asked about or names one twice
     */
    public static <T> Map<UUID, T> parse(JsonNode body, List<UUID> asked, String idField, BiFunction<UUID, JsonNode, T> rowReader) throws IOException {
        if (body == null || !body.has("results") || !body.get("results").isArray()) {
            throw new IOException("malformed answer");
        }
        Map<UUID, T> answer = new HashMap<>();
        for (JsonNode row : body.get("results")) {
            UUID id;
            T read;
            try {
                id = UUID.fromString(row.path(idField).asText());
                read = rowReader.apply(id, row);
            } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException e) {
                throw new IOException("malformed answer", e);
            }
            if (!asked.contains(id) || answer.put(id, read) != null) {
                throw new IOException("answer names an id that was not asked about, or twice");
            }
        }
        if (answer.size() != asked.size()) {
            throw new IOException("answer is incomplete");
        }
        return answer;
    }

    /** A whole, non-negative number; anything else is not an answer. */
    public static long count(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || !v.isIntegralNumber() || v.asLong() < 0) {
            throw new IllegalArgumentException(field + " is not a count");
        }
        return v.asLong();
    }
}
