CREATE TABLE finance.fee_structures (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    program_id          UUID NULL REFERENCES catalog.programs (id),
    name                TEXT NOT NULL,
    academic_year_id    UUID NULL REFERENCES catalog.academic_years (id),
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE finance.fee_components (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fee_structure_id    UUID NOT NULL REFERENCES finance.fee_structures (id),
    code                TEXT NOT NULL,
    amount              NUMERIC(12,2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    CONSTRAINT uq_fee_components_structure_code UNIQUE (fee_structure_id, code)
);

CREATE TABLE finance.student_charges (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    student_id          UUID NOT NULL REFERENCES directory.student_profiles (id),
    fee_component_id    UUID NOT NULL REFERENCES finance.fee_components (id),
    amount              NUMERIC(12,2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    due_date            DATE NULL,
    status              TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PAID','WAIVED','OVERDUE'))
);
CREATE INDEX idx_student_charges_student ON finance.student_charges (student_id);

CREATE TABLE finance.student_accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    student_id  UUID NOT NULL REFERENCES directory.student_profiles (id),
    balance     NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency    CHAR(3) NOT NULL DEFAULT 'INR',
    CONSTRAINT uq_student_accounts_tenant_student UNIQUE (tenant_id, student_id)
);
