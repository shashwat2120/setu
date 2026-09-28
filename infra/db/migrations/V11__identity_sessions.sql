-- family_id added here per the already-decided refresh-token-reuse strategy
-- in docs/transaction-concurrency-matrix.md scenario 7 ("recommend a new row
-- per rotation, linked by a family_id, so 'revoke the whole family' is a
-- single UPDATE ... WHERE family_id = ?") — that design was never carried
-- back into the ERD's column list until now; applying it, not inventing it.
CREATE TABLE identity.sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES identity.users (id),
    family_id           UUID NOT NULL,
    refresh_token_hash  TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ NULL,
    device_metadata     JSONB NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_sessions_refresh_token_hash ON identity.sessions (refresh_token_hash);
CREATE INDEX idx_sessions_user_expires ON identity.sessions (user_id, expires_at);
CREATE INDEX idx_sessions_family ON identity.sessions (family_id);
