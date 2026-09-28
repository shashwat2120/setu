CREATE TABLE identity.users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               CITEXT NOT NULL,
    phone               TEXT NULL,
    username            CITEXT NULL,
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','DEACTIVATED')),
    email_verified_at   TIMESTAMPTZ NULL,
    last_login_at       TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_users_email ON identity.users (email);
CREATE UNIQUE INDEX uq_users_username ON identity.users (username) WHERE username IS NOT NULL;
CREATE INDEX idx_users_status ON identity.users (status);
