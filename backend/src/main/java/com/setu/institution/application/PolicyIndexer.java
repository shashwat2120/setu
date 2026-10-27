package com.setu.institution.application;

import com.setu.institution.domain.PolicyChunker;
import com.setu.institution.infrastructure.PolicyChunkStore;
import com.setu.shared.ai.TextEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the policy search index in step with the documents: chunks a document when it is created, and in the background chunks any
 * document that has none and (re-)embeds any chunk that has no vector or was embedded by a different model than the one now configured.
 * An embedder that is down never blocks creating a document: the chunk is stored without a vector, found by full-text search at once,
 * and given its vector on a later pass.
 * <p>
 * <b>A provider that does not answer is left alone.</b> The embedder used here goes straight to the provider (it is not behind the query cache and its
 * cool-down, so a passage is never indexed from a cool-down's silence). With a provider over its quota that used to mean every waiting chunk, one after
 * another, each waiting out the 8 s timeout, on every pass: dozens of failed calls an hour and the same warning in the logs each time. Now a pass stops at the
 * first chunk the provider does not answer for, and the scheduled pass then stays away for a growing time (2, 4, 8, 16, 32 minutes, at most an hour) until
 * the provider answers again. A direct call to {@link #indexPending()} (a test, an administrator) is never held back by that wait.
 */
@Component
public class PolicyIndexer {

    private static final Logger log = LoggerFactory.getLogger(PolicyIndexer.class);
    private static final int BATCH = 25;
    /** Upper bound on batches per scheduled run, so a large re-embedding after a model change finishes in a few runs without hogging a thread. */
    private static final int MAX_BATCHES_PER_RUN = 8;
    /** How long the scheduled pass stays away after the first failure; it doubles with each failure in a row up to {@link #MAX_PAUSE}. */
    static final Duration FIRST_PAUSE = Duration.ofMinutes(2);
    static final Duration MAX_PAUSE = Duration.ofMinutes(60);

    private final PolicyChunkStore store;
    private final TextEmbedder embedder;
    private final Clock clock;
    private int failuresInARow;
    private Instant pausedUntil = Instant.EPOCH;

    @Autowired
    public PolicyIndexer(PolicyChunkStore store, TextEmbedder embedder) {
        this(store, embedder, Clock.systemUTC());
    }

    PolicyIndexer(PolicyChunkStore store, TextEmbedder embedder, Clock clock) {
        this.store = store;
        this.embedder = embedder;
        this.clock = clock;
    }

    /** Chunks and embeds one document. Safe to call twice: existing chunk positions are left as they are. */
    @Transactional
    public void indexDocument(UUID tenantId, UUID documentId, String content) {
        indexChunks(tenantId, documentId, content);
    }

    /** True when the embedder answered for every chunk it was asked about (or there was nothing to embed). After a chunk it does not answer for, the rest are stored without a vector. */
    private boolean indexChunks(UUID tenantId, UUID documentId, String content) {
        List<String> chunks = PolicyChunker.chunk(content);
        boolean embedderAnswered = true;
        for (int i = 0; i < chunks.size(); i++) {
            Optional<float[]> vector = embedderAnswered ? embedder.embed(chunks.get(i)) : Optional.empty();
            if (embedderAnswered && vector.isEmpty()) {
                embedderAnswered = false;   // do not wait out the provider's timeout again for each remaining chunk
            }
            store.insertChunk(tenantId, documentId, i, chunks.get(i),
                vector.map(PolicyChunkStore::literal).orElse(null), vector.isPresent() ? embedder.modelId() : null);
        }
        return embedderAnswered;
    }

    /** The scheduled entry point: does nothing while the provider is being left alone, otherwise one pass. */
    @Scheduled(initialDelayString = "${app.ai.embedding.index-initial-delay-ms:5000}", fixedDelayString = "${app.ai.embedding.index-interval-ms:60000}")
    public void indexPendingUnlessPaused() {
        if (clock.instant().isBefore(pausedUntil)) {
            return;
        }
        indexPending();
    }

    /** One pass: chunks documents that have none, then embeds waiting chunks until none are left or the provider stops answering. */
    public void indexPending() {
        try {
            for (UUID documentId : store.documentsWithoutChunks(BATCH)) {
                Optional<PolicyChunkStore.DocumentText> doc = store.documentText(documentId);
                if (doc.isPresent() && !indexChunks(doc.get().tenantId(), documentId, doc.get().content())) {
                    providerDidNotAnswer();
                    return;
                }
            }
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                List<PolicyChunkStore.PendingChunk> pending = store.chunksNeedingEmbedding(embedder.modelId(), BATCH);
                if (pending.isEmpty()) {
                    providerAnswered();
                    return;
                }
                for (PolicyChunkStore.PendingChunk chunk : pending) {
                    Optional<float[]> v = embedder.embed(chunk.content());
                    if (v.isEmpty()) {
                        providerDidNotAnswer();   // one failure is enough to know: the others would each wait out the same timeout
                        return;
                    }
                    store.setEmbedding(chunk.chunkId(), PolicyChunkStore.literal(v.get()), embedder.modelId());
                }
                providerAnswered();
            }
        } catch (RuntimeException e) {
            log.warn("policy index pass failed, will retry: {}", e.toString());
        }
    }

    private void providerAnswered() {
        failuresInARow = 0;
        pausedUntil = Instant.EPOCH;
    }

    private void providerDidNotAnswer() {
        failuresInARow++;
        long minutes = Math.min(MAX_PAUSE.toMinutes(), FIRST_PAUSE.toMinutes() << Math.min(failuresInARow - 1, 10));
        Duration pause = Duration.ofMinutes(minutes);
        pausedUntil = clock.instant().plus(pause);
        log.warn("policy index: the embedder ({}) did not answer; leaving it alone for {} minutes (failure {} in a row). Search keeps working from full-text.",
            embedder.modelId(), pause.toMinutes(), failuresInARow);
    }
}
