-- Append-only (docs/state-machines.md #19): never UPDATE a PUBLISHED row.
-- A correction inserts a new row with version = previous.version + 1 and
-- supersedes_id = previous.id; is_current is kept in sync in the same
-- transaction as the insert (the chosen resolution over ORDER BY version
-- DESC, for query simplicity — docs/ERD.md).
CREATE TABLE assessment.grades (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    enrollment_id   UUID NOT NULL REFERENCES enrollment.enrollments (id),
    component_id    UUID NOT NULL REFERENCES assessment.assessment_components (id),
    published_by    UUID NULL REFERENCES identity.users (id),
    raw_marks       NUMERIC(6,2) NOT NULL,
    grade_code      TEXT NULL,
    grade_point     NUMERIC(3,2) NULL,
    status          TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','SUPERSEDED')),
    published_at    TIMESTAMPTZ NULL,
    version         INT NOT NULL DEFAULT 1,
    supersedes_id   UUID NULL REFERENCES assessment.grades (id),
    is_current      BOOLEAN NOT NULL DEFAULT true
);
-- The hot "get current grade" path.
CREATE INDEX idx_grades_current ON assessment.grades (enrollment_id, component_id) WHERE is_current = true;

-- Deferred from V38 (learning.assignment_submissions) — see the note there.
-- grade_id can only be FK-constrained now that assessment.grades exists.
ALTER TABLE learning.assignment_submissions
    ADD CONSTRAINT fk_assignment_submissions_grade FOREIGN KEY (grade_id) REFERENCES assessment.grades (id);
