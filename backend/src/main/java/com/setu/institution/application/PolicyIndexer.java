package com.setu.institution.application;

import com.setu.institution.domain.PolicyChunker;
import com.setu.institution.infrastructure.PolicyChunkStore;
import com.setu.shared.ai.TextEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the policy search index in step with the documents: chunks a document when it is created, and in the background chunks any
 * document that has none and (re-)embeds any chunk that has no vector or was embedded by a different model than the one now configured.
 * An embedder that is down never blocks creating a document: the chunk is stored without a vector, found by full-text search at once,
 * and given its vector on a later pass.
 */
@Component
public class PolicyIndexer {

    private static final Logger log = LoggerFactory.getLogger(PolicyIndexer.class);
    private static final int BATCH = 25;
    /** Upper bound on batches per scheduled run, so a large re-embedding after a model change finishes in a few runs without hogging a thread. */
    private static final int MAX_BATCHES_PER_RUN = 8;

    private final PolicyChunkStore store;
    private final TextEmbedder embedder;

    public PolicyIndexer(PolicyChunkStore store, TextEmbedder embedder) {
        this.store = store;
        this.embedder = embedder;
    }

    /** Chunks and embeds one document. Safe to call twice: existing chunk positions are left as they are. */
    @Transactional
    public void indexDocument(UUID tenantId, UUID documentId, String content) {
        List<String> chunks = PolicyChunker.chunk(content);
        for (int i = 0; i < chunks.size(); i++) {
            Optional<float[]> vector = embedder.embed(chunks.get(i));
            store.insertChunk(tenantId, documentId, i, chunks.get(i),
                vector.map(PolicyChunkStore::literal).orElse(null), vector.isPresent() ? embedder.modelId() : null);
        }
    }

    @Scheduled(initialDelayString = "${app.ai.embedding.index-initial-delay-ms:5000}", fixedDelayString = "${app.ai.embedding.index-interval-ms:60000}")
    public void indexPending() {
        try {
            for (UUID documentId : store.documentsWithoutChunks(BATCH)) {
                store.documentText(documentId).ifPresent(doc -> indexDocument(doc.tenantId(), documentId, doc.content()));
            }
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                List<PolicyChunkStore.PendingChunk> pending = store.chunksNeedingEmbedding(embedder.modelId(), BATCH);
                int embedded = 0;
                for (PolicyChunkStore.PendingChunk chunk : pending) {
                    Optional<float[]> v = embedder.embed(chunk.content());
                    if (v.isPresent()) {
                        store.setEmbedding(chunk.chunkId(), PolicyChunkStore.literal(v.get()), embedder.modelId());
                        embedded++;
                    }
                }
                if (embedded == 0) {
                    break; // nothing left, or the embedder is down: try again on the next run
                }
            }
        } catch (RuntimeException e) {
            log.warn("policy index pass failed, will retry: {}", e.toString());
        }
    }
}
