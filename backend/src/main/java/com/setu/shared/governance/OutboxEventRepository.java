package com.setu.shared.governance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * {@code FOR UPDATE SKIP LOCKED}: the standard job-queue-safe read for a
     * poll-based relay — if this application is ever scaled to more than one
     * instance, two concurrent publisher runs pick disjoint batches instead
     * of racing to publish (and duplicate-mark) the same rows. Oldest first,
     * matching {@code idx_outbox_events_status_occurred}'s own partial index
     * (V4) exactly, so this query is index-only, not a sequential scan.
     */
    @Query(value = "SELECT * FROM governance.outbox_events WHERE status = 'NEW' "
        + "ORDER BY occurred_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockNextBatchForPublishing(@Param("batchSize") int batchSize);
}
