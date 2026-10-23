-- The Learning service's first migration of its own. Three lookups that every student dashboard, every risk recalculation and the AI advisor make, and that nothing indexed:
--   a student's submissions   (AssignmentSubmissionRepository.findByStudentId)
--   a student's lesson progress (LearningProgressRepository.findByStudentId)
--   a section's assignments     (AssignmentRepository.findBySectionId)
CREATE INDEX IF NOT EXISTS idx_assignment_submissions_student ON learning.assignment_submissions (student_id);
CREATE INDEX IF NOT EXISTS idx_learning_progress_student ON learning.learning_progress (student_id);
CREATE INDEX IF NOT EXISTS idx_assignments_section ON learning.assignments (section_id);
