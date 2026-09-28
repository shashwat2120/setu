-- Sprint 5B Day 3 gap-closure (see V51's header note — same disclosed
-- reasoning applies here). The WebAuthn ceremony (both registration and
-- authentication) requires a server-generated, single-use challenge that the
-- authenticator signs over; it must be looked up and expiry-checked at
-- verification time, and it must work across multiple backend instances, so
-- it's a table, not an in-memory map.
--
-- user_id is NULLABLE: a usernameless/discoverable-credential authentication
-- ceremony starts before the server knows which user is authenticating (the
-- authenticator supplies the WebAuthn user_handle only in its response) —
-- see identity.passkeys' credential_id lookup in the finish step, which
-- resolves the user, not this table.
CREATE TABLE identity.webauthn_challenges (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NULL REFERENCES identity.users (id),
    challenge    BYTEA NOT NULL,
    type         TEXT NOT NULL CHECK (type IN ('REGISTRATION', 'AUTHENTICATION')),
    rp_id        TEXT NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at  TIMESTAMPTZ NULL
);
CREATE INDEX idx_webauthn_challenges_user ON identity.webauthn_challenges (user_id);
-- The verification-time hot path: find this exact challenge, unconsumed, unexpired.
CREATE INDEX idx_webauthn_challenges_lookup ON identity.webauthn_challenges (id) WHERE consumed_at IS NULL;
