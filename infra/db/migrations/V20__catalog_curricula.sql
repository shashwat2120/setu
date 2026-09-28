CREATE TABLE catalog.curricula (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    program_id      UUID NOT NULL REFERENCES catalog.programs (id),
    name            TEXT NOT NULL,
    version_label   TEXT NOT NULL,
    effective_from  DATE NOT NULL,
    effective_to    DATE NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NOT NULL REFERENCES identity.users (id),
    version         INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_curricula_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX idx_curricula_program ON catalog.curricula (program_id);

-- Child of one tenant-owned parent (curricula) — no separate tenant_id per
-- the established rule (docs/erd-audit-2.md finding A only applies to joins
-- between two independently tenant-owned entities).
CREATE TABLE catalog.curriculum_requirements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    curriculum_id   UUID NOT NULL REFERENCES catalog.curricula (id),
    group_code      TEXT NOT NULL,
    min_credits     NUMERIC(6,2) NULL,
    min_courses     INT NULL,
    rule_json       JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_curriculum_requirements_curriculum ON catalog.curriculum_requirements (curriculum_id);
