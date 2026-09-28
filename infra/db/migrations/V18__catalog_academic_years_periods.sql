CREATE TABLE catalog.academic_years (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    code        TEXT NOT NULL,
    start_date  DATE NOT NULL,
    end_date    DATE NOT NULL,
    status      TEXT NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','CURRENT','CLOSED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID NOT NULL REFERENCES identity.users (id),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  UUID NOT NULL REFERENCES identity.users (id),
    version     INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_academic_years_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT chk_academic_years_dates CHECK (end_date > start_date)
);
-- At most one CURRENT year per tenant.
CREATE UNIQUE INDEX uq_academic_years_one_current ON catalog.academic_years (tenant_id) WHERE status = 'CURRENT';

CREATE TABLE catalog.academic_periods (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES institution.colleges (id),
    academic_year_id        UUID NOT NULL REFERENCES catalog.academic_years (id),
    code                    TEXT NOT NULL,
    name                    TEXT NOT NULL,
    sequence                INT NOT NULL,
    start_date              DATE NOT NULL,
    end_date                DATE NOT NULL,
    registration_open_at    TIMESTAMPTZ NULL,
    registration_close_at   TIMESTAMPTZ NULL,
    status                  TEXT NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','REGISTRATION_OPEN','REGISTRATION_CLOSED','IN_PROGRESS','CLOSED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID NOT NULL REFERENCES identity.users (id),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID NOT NULL REFERENCES identity.users (id),
    version                 INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_academic_periods_year_code UNIQUE (academic_year_id, code),
    CONSTRAINT chk_academic_periods_dates CHECK (end_date > start_date),
    CONSTRAINT chk_academic_periods_registration_window CHECK (registration_close_at IS NULL OR registration_open_at IS NULL OR registration_close_at > registration_open_at)
);
CREATE INDEX idx_academic_periods_tenant_status ON catalog.academic_periods (tenant_id, status);
