package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.AssessmentResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Isolates the two racy response writes in their own {@code REQUIRES_NEW}
 * transactions — covers Day 11 concurrency requirement 3 (concurrent
 * responses for the same question within one attempt): the first-ever
 * answer for a question is an INSERT (racing another concurrent first
 * answer), a changed answer before submission is a version-checked UPDATE
 * (racing another concurrent change). Both exceptions/false results must
 * propagate to the caller uncaught — see {@code AssessmentAttemptInsertGuard}'s
 * javadoc for the full "poisoned transaction" rationale.
 */
@Component
public class AssessmentResponseInsertGuard {

    private final AssessmentResponseRepository assessmentResponseRepository;

    public AssessmentResponseInsertGuard(AssessmentResponseRepository assessmentResponseRepository) {
        this.assessmentResponseRepository = assessmentResponseRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AssessmentResponse response) {
        assessmentResponseRepository.saveAndFlush(response);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateAnswerIfVersionMatches(UUID id, String answerJson, int expectedVersion) {
        return assessmentResponseRepository.updateAnswerIfVersionMatches(id, answerJson, expectedVersion) == 1;
    }
}
