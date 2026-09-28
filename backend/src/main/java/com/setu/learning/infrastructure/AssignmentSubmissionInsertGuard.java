package com.setu.learning.infrastructure;

import com.setu.learning.domain.AssignmentSubmission;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy submission INSERT in its own {@code REQUIRES_NEW}
 * transaction — same rationale as {@link LearningProgressUpsertGuard} and
 * Day 9's {@code AttendanceRecordInsertGuard}. A duplicate {@code
 * (assignment_id, student_id, attempt_no)} insert throws and must be left
 * to propagate to the caller uncaught.
 */
@Component
public class AssignmentSubmissionInsertGuard {

    private final AssignmentSubmissionRepository assignmentSubmissionRepository;

    public AssignmentSubmissionInsertGuard(AssignmentSubmissionRepository assignmentSubmissionRepository) {
        this.assignmentSubmissionRepository = assignmentSubmissionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AssignmentSubmission submission) {
        assignmentSubmissionRepository.saveAndFlush(submission);
    }
}
