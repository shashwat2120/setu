package com.setu.learning.domain;

/**
 * Matches learning.assignment_submissions.status's CHECK constraint (V38)
 * exactly — see docs/state-machines.md #16. Day 10 implements only the
 * {@code DRAFT → SUBMITTED}/{@code LATE} transition (the student submit
 * action); {@code GRADED} and {@code RESUBMISSION_REQUESTED} require the
 * grading workflow, explicitly Day 11+ scope ("do not let LMS write
 * grades") — no code in this module ever transitions a submission into
 * either state, though the column already allows the values for that
 * future work.
 */
public enum AssignmentSubmissionStatus {
    DRAFT, SUBMITTED, LATE, GRADED, RESUBMISSION_REQUESTED
}
