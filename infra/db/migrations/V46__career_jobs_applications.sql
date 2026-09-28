CREATE TABLE career.jobs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    posted_by   UUID NOT NULL REFERENCES identity.users (id),
    title       TEXT NOT NULL,
    employer    TEXT NULL,
    description TEXT NULL,
    status      TEXT NOT NULL DEFAULT 'OPEN'
);

-- tenant_id: cross-tenant join guard.
CREATE TABLE career.job_skills (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    job_id          UUID NOT NULL REFERENCES career.jobs (id),
    skill_id        UUID NOT NULL REFERENCES career.skills (id),
    required_level  TEXT NULL,
    CONSTRAINT uq_job_skills_job_skill UNIQUE (job_id, skill_id)
);

-- tenant_id: joins jobs + student_profiles, both independently tenant-owned
-- — the same erd-audit-2.md finding A pattern, caught here while writing the
-- real DDL (this specific table wasn't in the original flagged list).
CREATE TABLE career.applications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    job_id      UUID NOT NULL REFERENCES career.jobs (id),
    student_id  UUID NOT NULL REFERENCES directory.student_profiles (id),
    status      TEXT NOT NULL DEFAULT 'SUBMITTED',
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes       TEXT NULL,
    CONSTRAINT uq_applications_job_student UNIQUE (job_id, student_id)
);

-- Same reasoning — job_id nullable but still a cross-tenant-linkage risk when set.
CREATE TABLE career.placement_records (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    student_id  UUID NOT NULL REFERENCES directory.student_profiles (id),
    job_id      UUID NULL REFERENCES career.jobs (id),
    employer    TEXT NULL,
    role        TEXT NULL,
    ctc         NUMERIC(12,2) NULL,
    placed_at   DATE NULL
);
CREATE INDEX idx_placement_records_student ON career.placement_records (student_id);
