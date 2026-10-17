-- 6C.1: the trace that caused an event travels with it. An event is written inside the request that causes it and published a moment later by the
-- outbox poller, on another thread; storing the W3C traceparent of the writing span lets the consumer's spans continue that trace
-- (docs/contracts/observability.md). Optional: null when tracing was off or no span was current. Only the well-formed form is ever stored.

ALTER TABLE governance.outbox_events ADD COLUMN trace_parent TEXT NULL;
ALTER TABLE governance.outbox_events ADD CONSTRAINT chk_outbox_trace_parent
    CHECK (trace_parent IS NULL OR trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
