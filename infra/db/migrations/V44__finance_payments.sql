CREATE TABLE finance.payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    student_account_id  UUID NOT NULL REFERENCES finance.student_accounts (id),
    amount              NUMERIC(12,2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    method              TEXT NULL,
    status              TEXT NOT NULL DEFAULT 'INITIATED' CHECK (status IN ('INITIATED','SUCCEEDED','FAILED','REFUNDED')),
    -- Provider's own opaque token. Never PAN/CVV anywhere in this schema.
    provider_ref        TEXT NULL
);
CREATE INDEX idx_payments_student_account ON finance.payments (student_account_id);

-- The idempotency guard for duplicate-webhook delivery
-- (transaction-concurrency-matrix.md scenario 5): unique provider_event_id
-- makes a second delivery of the same event a no-op insert conflict.
CREATE TABLE finance.payment_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id          UUID NOT NULL REFERENCES finance.payments (id),
    provider_event_id   TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    payload_json        JSONB NULL,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_events_provider_event UNIQUE (provider_event_id)
);

-- tenant_id added (deviating from the "child of one tenant-owned parent
-- doesn't need its own tenant_id" default) specifically because the ERD
-- states "receipt_no unique per tenant" — that scope can't be expressed
-- without the column. Applying an already-stated rule, not inventing one.
CREATE TABLE finance.receipts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    payment_id      UUID NOT NULL REFERENCES finance.payments (id),
    receipt_no      TEXT NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    document_ref    TEXT NULL,
    CONSTRAINT uq_receipts_tenant_receipt_no UNIQUE (tenant_id, receipt_no)
);
