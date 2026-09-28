-- colleges.id IS the tenant_id referenced everywhere else — this table does
-- not carry its own tenant_id column (docs/contradiction-audit.md #1).
-- NOTE: the ERD entry for `colleges` did not explicitly list an audit-fields
-- line (unlike most other tables). created_at/updated_at applied here per
-- ERD.md's stated global default ("every mutable table" gets them unless a
-- table's entry says otherwise); created_by/updated_by omitted since that
-- narrower rule was scoped to *tenant-owned* tables and colleges is
-- explicitly exempt from tenant ownership. Flagged as a judgment call in the
-- Day 1-2 report, not a silent invention.
CREATE TABLE institution.colleges (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','ARCHIVED')),
    timezone        TEXT NOT NULL,
    locale          TEXT NOT NULL DEFAULT 'en-IN',
    settings_json   JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_colleges_code UNIQUE (code)
);
