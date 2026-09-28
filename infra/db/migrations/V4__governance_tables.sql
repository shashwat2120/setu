-- governance.audit_logs — append-only, indefinite retention, never pruned by
-- application code (docs/erd/04-finance-career-success-platform.md).
-- tenant_id/actor_user_id nullable to allow platform-level (SUPER_ADMIN) entries.
CREATE TABLE governance.audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NULL,
    actor_user_id   UUID NULL,
    action          TEXT NOT NULL,
    resource_type   TEXT NOT NULL,
    resource_id     UUID NULL,
    before_json     JSONB NULL,
    after_json      JSONB NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_resource ON governance.audit_logs (resource_type, resource_id);
CREATE INDEX idx_audit_logs_tenant_created ON governance.audit_logs (tenant_id, created_at);

-- governance.outbox_events — written in the SAME transaction as the business
-- change it describes (docs/TRD.md §4, canonical spec §8). Every module
-- writes here as part of its own transaction; there is no single owning module.
CREATE TABLE governance.outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NULL,
    aggregate_type  TEXT NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      TEXT NOT NULL,
    event_version   INT NOT NULL DEFAULT 1,
    payload_json    JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','PUBLISHED','FAILED')),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id  UUID NOT NULL,
    causation_id    UUID NULL
);
-- The outbox publisher's hot query: unpublished rows, oldest first.
CREATE INDEX idx_outbox_events_status_occurred ON governance.outbox_events (status, occurred_at)
    WHERE status = 'NEW';
CREATE INDEX idx_outbox_events_aggregate ON governance.outbox_events (aggregate_type, aggregate_id);

-- governance.idempotency_keys — API-request-level idempotency only (a
-- client-supplied Idempotency-Key header), distinct from payment webhook
-- dedup (payment_events.provider_event_id) and Kafka consumer dedup (each
-- consumer's own processed-event table) — see docs/erd-audit-2.md finding D.
CREATE TABLE governance.idempotency_keys (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key         TEXT NOT NULL,
    scope       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_key_scope UNIQUE (key, scope)
);
