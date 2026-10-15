package com.setu.institution.domain;

import com.setu.aiexplain.infrastructure.embedding.HashingTextEmbedder;
import com.setu.shared.ai.TextEmbedder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The two pure building blocks of policy retrieval: how a document is cut into passages, and how the built-in embedder behaves. */
class PolicyChunkerAndEmbedderTests {

    private final HashingTextEmbedder embedder = new HashingTextEmbedder();

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    @Test
    void aShortDocumentIsOneChunkAndNothingIsLost() {
        List<String> chunks = PolicyChunker.chunk("Students must maintain 75% attendance.");

        assertThat(chunks).containsExactly("Students must maintain 75% attendance.");
    }

    @Test
    void blankOrMissingTextGivesNoChunks() {
        assertThat(PolicyChunker.chunk(null)).isEmpty();
        assertThat(PolicyChunker.chunk("   \n\n  ")).isEmpty();
    }

    @Test
    void paragraphsAreSeparateChunks() {
        assertThat(PolicyChunker.chunk("First rule applies here.\n\nSecond rule applies there.")).hasSize(2);
    }

    @Test
    void aLongParagraphIsSplitOnSentencesWithinTheTargetAndConsecutiveChunksShareASentence() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            text.append("Sentence number ").append(i).append(" explains one more detail of the policy. ");
        }

        List<String> chunks = PolicyChunker.chunk(text.toString());

        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(PolicyChunker.TARGET_CHARS + 80).isPositive());
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1);
            String lastSentenceOfPrevious = previous.substring(previous.lastIndexOf("Sentence number"));
            assertThat(chunks.get(i)).as("overlap so a boundary does not cut an idea in two").startsWith(lastSentenceOfPrevious);
        }
        assertThat(String.join(" ", chunks)).contains("Sentence number 1 ").contains("Sentence number 40 ");
    }

    @Test
    void aSingleSentenceLongerThanTheTargetIsCutAtWordBoundaries() {
        String word = "regulation ";
        List<String> chunks = PolicyChunker.chunk(word.repeat(200).strip());

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c).doesNotStartWith("egulation").endsWith("regulation"));
    }

    @Test
    void theEmbedderIsDeterministicAndProducesUnitVectorsOfTheContractedSize() {
        float[] a = embedder.embed("Students must keep seventy five percent attendance").orElseThrow();
        float[] b = embedder.embed("Students must keep seventy five percent attendance").orElseThrow();

        assertThat(a).hasSize(TextEmbedder.DIMENSIONS).isEqualTo(b);
        assertThat(cosine(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void relatedWordingScoresAboveTheFloorAndUnrelatedWordingBelowIt() {
        float[] policy = embedder.embed("Students must maintain at least 75% attendance in every enrolled section to remain eligible").orElseThrow();
        float[] related = embedder.embed("attendance requirement for enrolled sections").orElseThrow();
        float[] unrelated = embedder.embed("parking permits for bicycles near the library").orElseThrow();

        assertThat(cosine(policy, related)).isGreaterThan(embedder.minSimilarity());
        assertThat(cosine(policy, unrelated)).isLessThan(embedder.minSimilarity());
        assertThat(cosine(policy, related)).isGreaterThan(cosine(policy, unrelated));
    }

    @Test
    void commonWordEndingsAreFoldedTogether() {
        float[] grading = embedder.embed("grading").orElseThrow();
        float[] graded = embedder.embed("graded").orElseThrow();

        assertThat(cosine(grading, graded)).isGreaterThan(0.99);
    }

    @Test
    void textWithNothingToEmbedGivesNoVector() {
        assertThat(embedder.embed("")).isEmpty();
        assertThat(embedder.embed(null)).isEmpty();
        assertThat(embedder.embed("the of and")).as("only stop words").isEmpty();
    }
}
