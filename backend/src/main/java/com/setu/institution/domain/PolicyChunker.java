package com.setu.institution.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a policy document into passages small enough to retrieve on their own. Splits on paragraph breaks first, then on sentence
 * ends, and packs sentences up to {@link #TARGET_CHARS}; consecutive chunks of a long paragraph share the last sentence so an idea that
 * straddles a boundary is still found whole. Never splits inside a word and never returns an empty chunk.
 */
public final class PolicyChunker {

    public static final int TARGET_CHARS = 600;

    private PolicyChunker() {
    }

    public static List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        for (String paragraph : content.split("\\R\\s*\\R")) {
            String text = paragraph.strip().replaceAll("\\s+", " ");
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() <= TARGET_CHARS) {
                chunks.add(text);
                continue;
            }
            List<String> sentences = sentences(text);
            StringBuilder current = new StringBuilder();
            String lastSentence = "";
            for (String sentence : sentences) {
                if (current.length() > 0 && current.length() + 1 + sentence.length() > TARGET_CHARS) {
                    chunks.add(current.toString());
                    current = new StringBuilder(lastSentence);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(sentence);
                lastSentence = sentence;
            }
            if (current.length() > 0) {
                chunks.add(current.toString());
            }
        }
        return chunks;
    }

    /** Sentences, with any single sentence longer than the target cut at word boundaries. */
    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[.!?])\\s+")) {
            String sentence = s.strip();
            while (sentence.length() > TARGET_CHARS) {
                int cut = sentence.lastIndexOf(' ', TARGET_CHARS);
                if (cut <= 0) {
                    cut = TARGET_CHARS;
                }
                out.add(sentence.substring(0, cut).strip());
                sentence = sentence.substring(cut).strip();
            }
            if (!sentence.isEmpty()) {
                out.add(sentence);
            }
        }
        return out;
    }
}
