CREATE TABLE learning.learning_progress (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id       UUID NOT NULL REFERENCES learning.lessons (id),
    student_id      UUID NOT NULL REFERENCES directory.student_profiles (id),
    state           TEXT NULL,
    percent         NUMERIC(5,2) NULL CHECK (percent IS NULL OR (percent BETWEEN 0 AND 100)),
    started_at      TIMESTAMPTZ NULL,
    completed_at    TIMESTAMPTZ NULL,
    last_position   JSONB NULL,
    CONSTRAINT uq_learning_progress_lesson_student UNIQUE (lesson_id, student_id)
);

CREATE TABLE learning.assignments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    section_id  UUID NOT NULL REFERENCES enrollment.course_sections (id),
    title       TEXT NOT NULL,
    max_marks   NUMERIC(6,2) NULL,
    due_at      TIMESTAMPTZ NULL,
    late_policy JSONB NULL,
    status      TEXT NOT NULL DEFAULT 'DRAFT'
);

-- grade_id references assessment.grades, which does not exist yet at this
-- point in the migration order (created in V47, after `learning`). Column
-- created here as plain UUID; the FK constraint itself is added once
-- assessment.grades exists (see the note in that migration). Flagged as a
-- migration-order deviation in the Day 1-2 report, not an ERD change — the
-- column and its eventual target are exactly as documented.
CREATE TABLE learning.assignment_submissions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id   UUID NOT NULL REFERENCES learning.assignments (id),
    student_id      UUID NOT NULL REFERENCES directory.student_profiles (id),
    grade_id        UUID NULL,
    attempt_no      INT NOT NULL DEFAULT 1,
    submitted_at    TIMESTAMPTZ NULL,
    status          TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','LATE','GRADED','RESUBMISSION_REQUESTED')),
    content_ref     TEXT NULL,
    CONSTRAINT uq_assignment_submissions_assignment_student_attempt UNIQUE (assignment_id, student_id, attempt_no)
);
