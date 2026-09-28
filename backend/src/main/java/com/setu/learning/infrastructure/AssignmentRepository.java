package com.setu.learning.infrastructure;

import com.setu.learning.domain.Assignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    List<Assignment> findBySectionId(UUID sectionId);

    /**
     * Day 10 concurrency requirement 4 (submission racing a close): both
     * {@code AssignmentSubmissionService.submit} and {@code
     * AssignmentService.close} take this row lock as their first read, the
     * same {@code SELECT ... FOR UPDATE} idiom {@code CourseSectionRepository
     * .findByIdForUpdate} established in Day 8 — serializes the two
     * operations against each other deterministically instead of leaving a
     * true check-then-act race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Assignment a where a.id = :id")
    Optional<Assignment> findByIdForUpdate(@Param("id") UUID id);
}
