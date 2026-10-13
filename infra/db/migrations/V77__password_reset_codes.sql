-- One-time codes for "forgot password": a short numeric code emailed to the account owner, entered together with the email address on the
-- reset page. Replaces emailing a reset link (identity.password_reset_tokens stays, for seeding the demo accounts' first passwords).
--
-- Only an HMAC of the code is stored (keyed with a server-side secret that is never in the database, bound to the user), so reading
-- this table alone cannot reveal a usable code. A six-digit code is guessable by brute force, so the guess budget is enforced here, in
-- the data: attempts is incremented atomically BEFORE a guess is compared, and a code with attempts >= the cap is never accepted.
--
-- No version column: every change is a single atomic UPDATE (record an attempt, consume, invalidate), each guarded by its own
-- predicate, so concurrent requests cannot both succeed or exceed the cap; optimistic locking would add nothing.
CREATE TABLE identity.password_reset_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES identity.users (id),
    code_hash   TEXT NOT NULL,
    attempts    INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_reset_codes_user_active ON identity.password_reset_codes (user_id, created_at DESC) WHERE used_at IS NULL;

COMMENT ON COLUMN identity.password_reset_codes.code_hash IS
    'HMAC-SHA256 (hex) of "userId:code", keyed with a server-side secret. Never the code itself.';
COMMENT ON COLUMN identity.password_reset_codes.attempts IS
    'Guesses made against this code, counted before comparing. The application caps this (5); at the cap the code is dead.';
