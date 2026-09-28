CREATE TABLE catalog.programs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    department_id       UUID NOT NULL REFERENCES institution.departments (id),
    code                TEXT NOT NULL,
    name                TEXT NOT NULL,
    degree_type         TEXT NULL,
    duration_terms      INT NULL,
    credits_required    NUMERIC(6,2) NULL,
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID NOT NULL REFERENCES identity.users (id),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID NOT NULL REFERENCES identity.users (id),
    version             INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_programs_tenant_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_programs_department ON catalog.programs (department_id);
