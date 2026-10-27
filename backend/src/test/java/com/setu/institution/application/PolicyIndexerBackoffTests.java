package com.setu.institution.application;

import com.setu.institution.infrastructure.PolicyChunkStore;
import com.setu.shared.ai.TextEmbedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A provider that does not answer (over its quota, down) must not be asked again for every waiting chunk on every pass. The embedder the indexer uses goes
 * straight to the provider and each unanswered call waits out its timeout, so with four chunks waiting and a pass a minute that was dozens of failed calls an
 * hour and the same warning each time. These tests use a plain mocked store and embedder and a clock the test moves, so they need no database and no waiting.
 */
class PolicyIndexerBackoffTests {

    private final PolicyChunkStore store = mock(PolicyChunkStore.class);
    private final TextEmbedder embedder = mock(TextEmbedder.class);
    private final MutableClock clock = new MutableClock();
    private PolicyIndexer indexer;

    /** A clock that only moves when the test says so. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static List<PolicyChunkStore.PendingChunk> waiting(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> new PolicyChunkStore.PendingChunk(UUID.randomUUID(), "passage " + i)).toList();
    }

    @BeforeEach
    void setUp() {
        when(embedder.modelId()).thenReturn("test-model");
        when(store.documentsWithoutChunks(25)).thenReturn(List.of());
        when(store.chunksNeedingEmbedding("test-model", 25)).thenReturn(waiting(4));
        indexer = new PolicyIndexer(store, embedder, clock);
    }

    @Test
    void aProviderThatDoesNotAnswerIsAskedOncePerPassNotOncePerWaitingChunk() {
        when(embedder.embed(anyString())).thenReturn(Optional.empty());

        indexer.indexPending();

        verify(embedder, times(1)).embed(anyString());   // four chunks waiting, one call
        verify(store, never()).setEmbedding(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    @Test
    void theScheduledPassStaysAwayWhileTheProviderIsBeingLeftAlone() {
        when(embedder.embed(anyString())).thenReturn(Optional.empty());

        indexer.indexPendingUnlessPaused();          // fails: leave it alone for 2 minutes
        clock.advance(Duration.ofMinutes(1));
        indexer.indexPendingUnlessPaused();          // still inside the wait: nothing is asked
        verify(embedder, times(1)).embed(anyString());

        clock.advance(Duration.ofMinutes(1));        // 2 minutes after the failure
        indexer.indexPendingUnlessPaused();
        verify(embedder, times(2)).embed(anyString());
    }

    @Test
    void eachFailureInARowWaitsLongerThanTheLast() {
        when(embedder.embed(anyString())).thenReturn(Optional.empty());

        indexer.indexPendingUnlessPaused();          // failure 1: waits 2 minutes
        clock.advance(Duration.ofMinutes(2));
        indexer.indexPendingUnlessPaused();          // failure 2: waits 4 minutes
        verify(embedder, times(2)).embed(anyString());

        clock.advance(Duration.ofMinutes(3));
        indexer.indexPendingUnlessPaused();          // 3 minutes is not enough any more
        verify(embedder, times(2)).embed(anyString());

        clock.advance(Duration.ofMinutes(1));        // 4 minutes
        indexer.indexPendingUnlessPaused();
        verify(embedder, times(3)).embed(anyString());
    }

    @Test
    void theWaitNeverGrowsPastAnHour() {
        when(embedder.embed(anyString())).thenReturn(Optional.empty());

        for (int failure = 0; failure < 12; failure++) {
            indexer.indexPendingUnlessPaused();
            clock.advance(PolicyIndexer.MAX_PAUSE);   // always enough to be allowed to try again
        }

        verify(embedder, times(12)).embed(anyString());   // every pass after a full hour was allowed, so the wait is capped at an hour
    }

    @Test
    void anAnswerEndsTheWaitAndStartsTheCountAgain() {
        float[] vector = new float[TextEmbedder.DIMENSIONS];
        when(embedder.embed(anyString())).thenReturn(Optional.empty());
        indexer.indexPendingUnlessPaused();          // failure 1: 2 minutes
        clock.advance(Duration.ofMinutes(2));
        indexer.indexPendingUnlessPaused();          // failure 2: 4 minutes
        clock.advance(Duration.ofMinutes(4));

        when(embedder.embed(anyString())).thenReturn(Optional.of(vector));
        when(store.chunksNeedingEmbedding("test-model", 25)).thenReturn(waiting(2)).thenReturn(List.of());
        indexer.indexPendingUnlessPaused();          // the provider is back: both chunks get a vector
        verify(store, times(2)).setEmbedding(org.mockito.ArgumentMatchers.any(), anyString(), anyString());

        when(embedder.embed(anyString())).thenReturn(Optional.empty());
        when(store.chunksNeedingEmbedding("test-model", 25)).thenReturn(waiting(1));
        indexer.indexPendingUnlessPaused();          // a new failure starts again at 2 minutes, not 8
        clock.advance(Duration.ofMinutes(2));
        indexer.indexPendingUnlessPaused();
        verify(embedder, times(6)).embed(anyString());   // 1 + 1 failed, 2 answered, then 1 + 1 failed: the last is allowed after only 2 minutes
    }

    @Test
    void aDirectCallIsNeverHeldBackByTheWait() {
        when(embedder.embed(anyString())).thenReturn(Optional.empty());
        indexer.indexPendingUnlessPaused();          // now being left alone

        indexer.indexPending();                      // a test or an administrator asking directly

        verify(embedder, times(2)).embed(anyString());
    }

    @Test
    void aNewDocumentWithTheProviderDownIsAskedAboutOnlyItsFirstChunk() {
        UUID tenant = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        String longText = ("A sentence of policy text that is long enough to count. ").repeat(60);   // several chunks
        when(embedder.embed(anyString())).thenReturn(Optional.empty());

        indexer.indexDocument(tenant, document, longText);

        verify(embedder, times(1)).embed(anyString());
        verify(store, org.mockito.Mockito.atLeast(2)).insertChunk(org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.eq(document),
            org.mockito.ArgumentMatchers.anyInt(), anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
    }
}
