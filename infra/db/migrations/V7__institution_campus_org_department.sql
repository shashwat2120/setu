CREATE TABLE institution.campuses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    college_id      UUID NOT NULL REFERENCES institution.colleges (id),
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    address_json    JSONB NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_campuses_tenant_code UNIQUE (tenant_id, code),
    -- college_id and tenant_id are always equal by construction (ERD note) —
    -- kept as two columns for join readability, enforced equal here.
    CONSTRAINT chk_campuses_college_is_tenant CHECK (college_id = tenant_id)
);

CREATE TABLE institution.academic_organizations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    campus_id       UUID NOT NULL REFERENCES institution.campuses (id),
    name            TEXT NOT NULL,
    type            TEXT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_academic_organizations_campus ON institution.academic_organizations (campus_id);

CREATE TABLE institution.departments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    organization_id UUID NOT NULL REFERENCES institution.academic_organizations (id),
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    -- hod_user_id crosses into identity — validated by the identity/directory
    -- application services (module boundary rule), not a business-logic FK.
    hod_user_id     UUID NULL REFERENCES identity.users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_departments_tenant_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_departments_organization ON institution.departments (organization_id);
