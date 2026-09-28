-- Day 11 concurrency requirement 3: a response row (once it already exists)
-- can be legitimately re-answered while the attempt is still IN_PROGRESS,
-- so a plain UPDATE with no version check would let two concurrent
-- answer-changes silently overwrite one another. Same optimistic-lock
-- guard attendance_records (Day 9) and learning_progress (Day 10) gained
-- for the identical reason.
ALTER TABLE assessment.assessment_responses ADD COLUMN version INT NOT NULL DEFAULT 0;
