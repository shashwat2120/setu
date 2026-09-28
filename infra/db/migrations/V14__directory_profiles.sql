CREATE TABLE directory.student_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    person_id       UUID NOT NULL REFERENCES identity.persons (id),
    admission_no    TEXT NOT NULL,
    roll_no         TEXT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ON_LEAVE','SUSPENDED','GRADUATED','WITHDRAWN')),
    admission_date  DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_student_profiles_tenant_admission UNIQUE (tenant_id, admission_no),
    CONSTRAINT uq_student_profiles_tenant_person UNIQUE (tenant_id, person_id)
);
CREATE INDEX idx_student_profiles_tenant_roll ON directory.student_profiles (tenant_id, roll_no);

CREATE TABLE directory.faculty_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    person_id       UUID NOT NULL REFERENCES identity.persons (id),
    employee_no     TEXT NOT NULL,
    designation     TEXT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ON_LEAVE','SUSPENDED','TERMINATED')),
    joined_at       DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_faculty_profiles_tenant_employee UNIQUE (tenant_id, employee_no),
    CONSTRAINT uq_faculty_profiles_tenant_person UNIQUE (tenant_id, person_id)
);

-- `status` left as free TEXT (no enumerated CHECK) — the ERD did not specify
-- guardians' exact status vocabulary; not inventing one.
CREATE TABLE directory.guardians (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    person_id       UUID NOT NULL REFERENCES identity.persons (id),
    occupation      TEXT NULL,
    organization    TEXT NULL,
    address_json    JSONB NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_guardians_tenant_person UNIQUE (tenant_id, person_id)
);
