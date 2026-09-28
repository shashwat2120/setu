CREATE TABLE enrollment.enrollments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    section_id      UUID NOT NULL REFERENCES enrollment.course_sections (id),
    student_id      UUID NOT NULL REFERENCES directory.student_profiles (id),
    status          TEXT NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','ENROLLED','WAITLISTED','DROPPED','COMPLETED','REJECTED')),
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    enrolled_at     TIMESTAMPTZ NULL,
    dropped_at      TIMESTAMPTZ NULL,
    final_status    TEXT NULL,
    version         INT NOT NULL DEFAULT 0,
    -- One enrollment row per student per section, ever — state transitions
    -- happen on this one row, not a new row per attempt (docs/erd/02-academic.md).
    CONSTRAINT uq_enrollments_section_student UNIQUE (section_id, student_id)
);
-- Capacity-counting query (transaction-concurrency-matrix.md scenario 1).
CREATE INDEX idx_enrollments_section_status ON enrollment.enrollments (section_id, status);
-- "My enrollments" query.
CREATE INDEX idx_enrollments_student_status ON enrollment.enrollments (student_id, status);
