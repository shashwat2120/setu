-- Which component of a section carries the course's final grade (a decision of the college: publishing a final grade completes the enrollment).
-- At most one per section; that rule spans assessments, so it is enforced by the service under a per-section lock rather than by an index here.
ALTER TABLE assessment.assessment_components ADD COLUMN is_final BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN assessment.assessment_components.is_final IS
    'This component''s published grade is the course''s final grade: publishing it completes the student''s enrollment. At most one per section.';
