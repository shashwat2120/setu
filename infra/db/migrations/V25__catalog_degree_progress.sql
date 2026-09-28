-- Moved here from `record` per approved decision H3 (curriculum-driven,
-- belongs with Institution/Academic). Derived/recomputable projection
-- (docs/erd/03-learning-assessment-records.md) — a unique (student_id,
-- curriculum_id) is added here as a necessary inference, not a new
-- relationship: without it, "recomputable" would accumulate duplicate rows
-- on every recalculation instead of updating one row per student+curriculum.
CREATE TABLE catalog.degree_progress (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    student_id          UUID NOT NULL REFERENCES directory.student_profiles (id),
    curriculum_id       UUID NOT NULL REFERENCES catalog.curricula (id),
    credits_required    NUMERIC(6,2) NULL,
    credits_earned      NUMERIC(6,2) NULL,
    percentage          NUMERIC(5,2) NULL,
    calculated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_degree_progress_student_curriculum UNIQUE (student_id, curriculum_id)
);
