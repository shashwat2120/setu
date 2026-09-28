package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.Grade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GradeRepository extends JpaRepository<Grade, UUID> {

    /** Safe to read unlocked once the caller already holds the owning component's lock — see {@code GradeService#publishOrCorrect}'s javadoc. */
    Optional<Grade> findByEnrollmentIdAndComponentIdAndIsCurrentTrue(UUID enrollmentId, UUID componentId);

    List<Grade> findByEnrollmentIdAndComponentIdOrderByVersionDesc(UUID enrollmentId, UUID componentId);

    List<Grade> findByEnrollmentIdAndIsCurrentTrue(UUID enrollmentId);
}
