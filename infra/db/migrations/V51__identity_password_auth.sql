-- Sprint 5B Day 3 gap-closure, disclosed per the Day 3 report: the canonical
-- schema (V1-V50) has no column anywhere to store a password hash and no
-- table for password-reset tokens, even though the canonical spec's stack
-- (Passkeys/WebAuthn primary + Argon2id fallback) was already locked before
-- Sprint 5A. This is not a new architectural decision — it's finishing what
-- the locked stack already implies — so it's applied directly rather than
-- stopped-and-reported, the same way V50's event_publication table was.
--
-- password_hash is NULLABLE: a user who only ever registers a passkey and
-- never sets a password has no row here to fill — passkey remains usable
-- with no fallback configured, which is a valid account state, not an error.
ALTER TABLE identity.users
    ADD COLUMN password_hash          TEXT NULL,
    ADD COLUMN password_algo          TEXT NULL CHECK (password_algo IN ('ARGON2ID')),
    ADD COLUMN password_updated_at    TIMESTAMPTZ NULL,
    ADD COLUMN failed_login_attempts  INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until           TIMESTAMPTZ NULL;

-- Account-level lockout after N consecutive failed password verifications
-- (the "rate limiting" requirement, scoped to what Identity itself can
-- enforce). Per-IP/global request-rate limiting is an API Gateway concern —
-- already recorded as its own Sprint 6 candidate service
-- (docs/sprint6-candidate-services.md) — and deliberately not duplicated
-- here.
COMMENT ON COLUMN identity.users.failed_login_attempts IS
    'Consecutive failed password verifications since the last success; reset to 0 on success. Drives locked_until.';
COMMENT ON COLUMN identity.users.locked_until IS
    'Account-level password lockout expiry set after repeated failures. NULL means not locked. Passkey login is never blocked by this.';

-- One-time, single-use, short-lived tokens for the "secure password
-- reset/recovery" requirement. Only the hash is stored (same pattern as
-- identity.sessions.refresh_token_hash) so a database read alone can never
-- yield a usable reset token.
CREATE TABLE identity.password_reset_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES identity.users (id),
    token_hash  TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_password_reset_tokens_hash ON identity.password_reset_tokens (token_hash);
CREATE INDEX idx_password_reset_tokens_user ON identity.password_reset_tokens (user_id) WHERE used_at IS NULL;
