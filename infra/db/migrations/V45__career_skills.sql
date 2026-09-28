CREATE TABLE career.skill_categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    code        TEXT NOT NULL,
    name        TEXT NOT NULL,
    CONSTRAINT uq_skill_categories_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE career.skills (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    category_id     UUID NULL REFERENCES career.skill_categories (id),
    CONSTRAINT uq_skills_tenant_code UNIQUE (tenant_id, code)
);

-- tenant_id: cross-tenant join guard (docs/erd-audit-2.md finding A pattern).
CREATE TABLE career.student_skills (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    student_id          UUID NOT NULL REFERENCES directory.student_profiles (id),
    skill_id            UUID NOT NULL REFERENCES career.skills (id),
    proficiency_level   TEXT NULL,
    evidence_ref        TEXT NULL,
    CONSTRAINT uq_student_skills_student_skill UNIQUE (student_id, skill_id)
);
