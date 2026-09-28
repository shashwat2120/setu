CREATE TABLE assessment.assessments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    section_id          UUID NOT NULL REFERENCES enrollment.course_sections (id),
    type                TEXT NULL,
    title               TEXT NOT NULL,
    total_marks         NUMERIC(6,2) NULL,
    duration_minutes    INT NULL,
    starts_at           TIMESTAMPTZ NULL,
    ends_at             TIMESTAMPTZ NULL,
    status              TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SCHEDULED','OPEN','CLOSED','PUBLISHED','VOIDED'))
);

CREATE TABLE assessment.assessment_components (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id   UUID NOT NULL REFERENCES assessment.assessments (id),
    code            TEXT NOT NULL,
    max_marks       NUMERIC(6,2) NOT NULL,
    weight          NUMERIC(5,2) NOT NULL,
    order_no        INT NULL,
    CONSTRAINT uq_assessment_components_assessment_code UNIQUE (assessment_id, code)
);

CREATE TABLE assessment.questions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id    UUID NOT NULL REFERENCES assessment.assessment_components (id),
    type            TEXT NULL,
    prompt          TEXT NOT NULL,
    points          NUMERIC(5,2) NOT NULL,
    difficulty      TEXT NULL,
    skill_id        UUID NULL,
    metadata_json   JSONB NULL
);
