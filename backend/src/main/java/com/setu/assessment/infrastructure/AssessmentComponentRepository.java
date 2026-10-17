package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.AssessmentComponent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentComponentRepository extends JpaRepository<AssessmentComponent, UUID> {

    List<AssessmentComponent> findByAssessmentId(UUID assessmentId);

    /** How many components across all of a section's assessments are marked as the final grade. */
    @Query("select count(c) from AssessmentComponent c, Assessment a where c.assessmentId = a.id and a.sectionId = :sectionId and c.finalGrade = true")
    long countFinalInSection(@Param("sectionId") UUID sectionId);

    /**
     * Serialization anchor for {@code GradeService#publishOrCorrect} — see
     * that method's javadoc for why a lock scoped to the mutable {@code
     * grades.is_current} row (the original design) cannot correctly
     * serialize against a concurrent first-time INSERT: PostgreSQL's
     * {@code SELECT ... FOR UPDATE} re-evaluates only the specific row(s)
     * it was blocked on, never a fresh table scan, so a blocked waiter can
     * unblock having missed a brand-new row the other transaction just
     * committed. This component row is stable (never inserted/deleted
     * during a grade operation), so locking it first fully serializes
     * every publish/correct call for the component before either reads
     * the current grade — no phantom-row gap possible.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AssessmentComponent c where c.id = :id")
    Optional<AssessmentComponent> findByIdForUpdate(@Param("id") UUID id);
}
