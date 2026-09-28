-- Append/preserve official history — never delete, never silently update a
-- posted row (same rule as assessment.grades).
CREATE TABLE record.student_academic_records (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    student_id          UUID NOT NULL REFERENCES directory.student_profiles (id),
    period_id           UUID NOT NULL REFERENCES catalog.academic_periods (id),
    enrollment_id       UUID NOT NULL REFERENCES enrollment.enrollments (id),
    credits_attempted   NUMERIC(6,2) NULL,
    credits_earned      NUMERIC(6,2) NULL,
    grade_point         NUMERIC(3,2) NULL,
    quality_points      NUMERIC(6,2) NULL
);
CREATE INDEX idx_student_academic_records_student_period ON record.student_academic_records (student_id, period_id);

-- Confirmed Record-owned (per your documentation clarification): the
-- student's official term snapshot. Derived-then-frozen: recomputed from
-- student_academic_records while posted_at IS NULL, frozen once posted.
CREATE TABLE record.student_academic_periods (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    student_id          UUID NOT NULL REFERENCES directory.student_profiles (id),
    period_id           UUID NOT NULL REFERENCES catalog.academic_periods (id),
    status              TEXT NOT NULL DEFAULT 'IN_PROGRESS',
    standing            TEXT NULL,
    term_gpa            NUMERIC(4,2) NULL,
    credits_attempted   NUMERIC(6,2) NULL,
    credits_earned      NUMERIC(6,2) NULL,
    posted_at           TIMESTAMPTZ NULL,
    CONSTRAINT uq_student_academic_periods_student_period UNIQUE (student_id, period_id)
);

CREATE TABLE record.transcripts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    student_id      UUID NOT NULL REFERENCES directory.student_profiles (id),
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT NOT NULL DEFAULT 1,
    document_ref    TEXT NULL,
    status          TEXT NOT NULL DEFAULT 'GENERATING'
);
CREATE INDEX idx_transcripts_student ON record.transcripts (student_id);

CREATE TABLE record.graduation_applications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    student_id          UUID NOT NULL REFERENCES directory.student_profiles (id),
    target_period_id    UUID NOT NULL REFERENCES catalog.academic_periods (id),
    status              TEXT NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at         TIMESTAMPTZ NULL
);
CREATE INDEX idx_graduation_applications_student ON record.graduation_applications (student_id);
