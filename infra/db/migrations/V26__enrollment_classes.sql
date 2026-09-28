CREATE TABLE enrollment.classes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    program_id      UUID NOT NULL REFERENCES catalog.programs (id),
    cohort_code     TEXT NOT NULL,
    intake_year     INT NOT NULL,
    name            TEXT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_classes_tenant_program_cohort_intake UNIQUE (tenant_id, program_id, cohort_code, intake_year)
);
