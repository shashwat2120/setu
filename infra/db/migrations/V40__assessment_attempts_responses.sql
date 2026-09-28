CREATE TABLE assessment.assessment_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id   UUID NOT NULL REFERENCES assessment.assessments (id),
    student_id      UUID NOT NULL REFERENCES directory.student_profiles (id),
    attempt_no      INT NOT NULL DEFAULT 1,
    started_at      TIMESTAMPTZ NULL,
    submitted_at    TIMESTAMPTZ NULL,
    status          TEXT NOT NULL DEFAULT 'NOT_STARTED' CHECK (status IN ('NOT_STARTED','IN_PROGRESS','SUBMITTED','EVALUATED','VOIDED')),
    -- Provisional attempt-level score — distinct from assessment.grades.raw_marks
    -- (the published, official figure). See docs/contradiction-audit.md #10.
    score           NUMERIC(6,2) NULL,
    CONSTRAINT uq_assessment_attempts_assessment_student_attempt UNIQUE (assessment_id, student_id, attempt_no)
);

CREATE TABLE assessment.assessment_responses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id      UUID NOT NULL REFERENCES assessment.assessment_attempts (id),
    question_id     UUID NOT NULL REFERENCES assessment.questions (id),
    answer_json     JSONB NULL,
    is_correct      BOOLEAN NULL,
    points_awarded  NUMERIC(5,2) NULL,
    CONSTRAINT uq_assessment_responses_attempt_question UNIQUE (attempt_id, question_id)
);
