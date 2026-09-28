package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.AssessmentResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentResponseRepository extends JpaRepository<AssessmentResponse, UUID> {

    List<AssessmentResponse> findByAttemptId(UUID attemptId);

    Optional<AssessmentResponse> findByAttemptIdAndQuestionId(UUID attemptId, UUID questionId);

    /**
     * Version-checked conditional UPDATE, deliberately not a load-then-{@code
     * save()} — same rationale as attendance's {@code correctIfVersionMatches}
     * (Day 9) / learning_progress's {@code updateIfVersionMatches} (Day 10):
     * sharing a Hibernate-managed entity across the caller's context and a
     * {@code REQUIRES_NEW} guard silently breaks. Returns rows updated: 1 on
     * success, 0 if {@code expectedVersion} no longer matches.
     */
    @Modifying
    @Query("update AssessmentResponse r set r.answerJson = :answerJson, r.version = r.version + 1 "
        + "where r.id = :id and r.version = :expectedVersion")
    int updateAnswerIfVersionMatches(@Param("id") UUID id, @Param("answerJson") String answerJson,
                                      @Param("expectedVersion") int expectedVersion);
}
