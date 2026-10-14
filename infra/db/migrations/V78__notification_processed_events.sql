-- The notification module's own processed-event table (canonical spec §8; docs/kafka-event-catalog.md "Idempotency"): every Kafka event
-- the module has already acted on is recorded here by event_id, in the same transaction as the notification it produced. Kafka delivers
-- at least once, so a redelivered event hits the primary key and is skipped instead of notifying the user twice.
--
-- No shared global table: each consumer module keeps its own, so extracting the module later takes its table with it.
CREATE TABLE notification.processed_events (
    event_id     UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE notification.processed_events IS
    'Events the notification consumer has already handled, keyed by the outbox event id. Written in the same transaction as the notification.';
