-- The enrollment module's own processed-event table (canonical spec section 8; docs/kafka-event-catalog.md "Idempotency"): every Kafka event
-- the enrollment consumer has already acted on is recorded here by event_id, in the same transaction as the change it caused. Kafka delivers at
-- least once, so a redelivered FinalGradePublished hits the primary key and is skipped instead of completing (or correcting) the enrollment twice.
--
-- No shared global table: each consumer module keeps its own, so extracting the module later takes its table with it.
CREATE TABLE enrollment.processed_events (
    event_id     UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE enrollment.processed_events IS
    'Events the enrollment consumer has already handled, keyed by the outbox event id. Written in the same transaction as the enrollment change.';
