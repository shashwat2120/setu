CREATE TABLE identity.passkeys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES identity.users (id),
    credential_id   BYTEA NOT NULL,
    public_key      BYTEA NOT NULL,
    sign_count      BIGINT NOT NULL DEFAULT 0,
    aaguid          UUID NULL,
    transports      TEXT[] NULL,
    backed_up       BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NULL,
    revoked_at      TIMESTAMPTZ NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED'))
);
CREATE UNIQUE INDEX uq_passkeys_credential_id ON identity.passkeys (credential_id);
CREATE INDEX idx_passkeys_user_active ON identity.passkeys (user_id) WHERE status = 'ACTIVE';
