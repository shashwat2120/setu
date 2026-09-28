package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.AssessmentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, UUID> {

    List<AssessmentAttempt> findByAssessmentIdAndStudentId(UUID assessmentId, UUID studentId);

    int countByAssessmentIdAndStudentId(UUID assessmentId, UUID studentId);

    Optional<AssessmentAttempt> findByAssessmentIdAndStudentIdAndAttemptNo(UUID assessmentId, UUID studentId, int attemptNo);

    List<AssessmentAttempt> findByAssessmentId(UUID assessmentId);
}
