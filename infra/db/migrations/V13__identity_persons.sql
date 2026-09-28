-- Same audit-field judgment call as colleges (V6): ERD entry states
-- "Version/locking: yes" but no explicit audit-fields line; created_at/
-- updated_at applied per the stated global default, created_by/updated_by
-- omitted since persons is explicitly not tenant-owned.
CREATE TABLE identity.persons (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NULL REFERENCES identity.users (id),
    legal_name      TEXT NOT NULL,
    preferred_name  TEXT NULL,
    dob             DATE NULL,
    gender          TEXT NULL,
    email           CITEXT NULL,
    phone           TEXT NULL,
    address_json    JSONB NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_persons_user_id ON identity.persons (user_id) WHERE user_id IS NOT NULL;
