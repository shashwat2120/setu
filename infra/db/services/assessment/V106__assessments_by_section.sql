-- The Assessment service's first migration of its own. A section's assessments are looked up by section on every faculty and student view of it and on every deadline
-- the AI advisor lists (AssessmentRepository.findBySectionId), and nothing indexed section_id: each of those read the whole table.
CREATE INDEX IF NOT EXISTS idx_assessments_section ON assessment.assessments (section_id);
