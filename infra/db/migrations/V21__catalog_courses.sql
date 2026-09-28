CREATE TABLE catalog.courses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    department_id   UUID NOT NULL REFERENCES institution.departments (id),
    code            TEXT NOT NULL,
    title           TEXT NOT NULL,
    credits         NUMERIC(4,1) NOT NULL,
    course_type     TEXT NULL,
    level           TEXT NULL,
    description     TEXT NULL,
    status          TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','DEPRECATED','RETIRED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_courses_tenant_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_courses_department ON catalog.courses (department_id);
