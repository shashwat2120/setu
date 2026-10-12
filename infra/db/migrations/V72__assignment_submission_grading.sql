-- Assignment grading/feedback: learning.assignment_submissions could reach GRADED (V38's CHECK already allowed
-- it) but nothing ever wrote marks or feedback, or moved a row into that status - "Day 11's grading workflow"
-- referenced in the entity's own javadoc never landed. This is a deliberately LMS-owned marks/feedback pair,
-- NOT routed through assessment.grades' versioned publish pipeline (that would need each assignment to carry a
-- provisioned assessment_id/component_id, which nothing currently creates) - disclosed scope trade-off, not an
-- oversight. version is optimistic-locking, matching every other mutable aggregate's own column of the same name.
ALTER TABLE learning.assignment_submissions
    ADD COLUMN marks       NUMERIC(6,2) NULL,
    ADD COLUMN feedback    TEXT NULL,
    ADD COLUMN graded_by   UUID NULL,
    ADD COLUMN graded_at   TIMESTAMPTZ NULL,
    ADD COLUMN version     INT NOT NULL DEFAULT 0;
