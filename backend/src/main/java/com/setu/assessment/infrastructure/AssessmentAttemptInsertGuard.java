package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.AssessmentAttempt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy attempt-creation INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL "poisoned transaction"
 * defense as Day 9's {@code AttendanceRecordInsertGuard} / Day 10's
 * {@code AssignmentSubmissionInsertGuard}. Covers Day 11 concurrency
 * requirements 1 (two submissions racing final attempt creation — i.e. the
 * last legal attempt slot) and 2 (same student concurrent attempt
 * creation). A duplicate {@code (assessment_id, student_id, attempt_no)}
 * insert throws and must be left to propagate to the caller uncaught.
 */
@Component
public class AssessmentAttemptInsertGuard {

    private final AssessmentAttemptRepository assessmentAttemptRepository;

    public AssessmentAttemptInsertGuard(AssessmentAttemptRepository assessmentAttemptRepository) {
        this.assessmentAttemptRepository = assessmentAttemptRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AssessmentAttempt attempt) {
        assessmentAttemptRepository.saveAndFlush(attempt);
    }
}
