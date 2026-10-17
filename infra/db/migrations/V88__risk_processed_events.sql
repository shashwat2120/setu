-- The risk module's own processed-event table (canonical spec section 8; docs/kafka-event-catalog.md "Idempotency"): every Kafka event the
-- risk consumer has already acted on is recorded here by event_id, in the same transaction as the recalculation it caused. Kafka delivers at
-- least once, so a redelivered GradePublished hits the primary key and is skipped instead of recalculating (and re-alerting) twice.
--
-- No shared global table: each consumer module keeps its own, so extracting the module later takes its table with it.
CREATE TABLE risk.processed_events (
    event_id     UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE risk.processed_events IS
    'Events the risk consumer has already handled, keyed by the outbox event id. Written in the same transaction as the recalculation.';
