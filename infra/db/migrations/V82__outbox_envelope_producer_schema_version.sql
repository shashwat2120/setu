-- The canonical event envelope (canonical spec section 8) is event_id, event_type, event_version, occurred_at, producer, tenant_id,
-- aggregate_type, aggregate_id, correlation_id, causation_id, schema_version, payload. The outbox row carried every field but two: who produced
-- the event, and the version of the envelope itself. Adding them (Sprint 6B.1) lets a consumer in another service see where an event came from
-- and refuse an envelope shape it does not understand, without parsing the payload.
--
--   producer        the module or service that wrote the event (the owner of its aggregate), for example "enrollment"
--   schema_version  the version of the ENVELOPE (header and field set), not of the event's payload; event_version is the payload's version
--
-- Rows written before this migration were all produced by the monolith's modules at envelope version 1.
ALTER TABLE governance.outbox_events
    ADD COLUMN producer       TEXT NOT NULL DEFAULT 'monolith',
    ADD COLUMN schema_version INT  NOT NULL DEFAULT 1;

COMMENT ON COLUMN governance.outbox_events.producer IS 'The module or service that produced the event.';
COMMENT ON COLUMN governance.outbox_events.schema_version IS 'Version of the event envelope, not of the payload (event_version).';
