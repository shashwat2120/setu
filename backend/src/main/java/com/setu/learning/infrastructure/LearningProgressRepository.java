package com.setu.learning.infrastructure;

import com.setu.learning.domain.LearningProgress;
import com.setu.learning.domain.LearningProgressState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LearningProgressRepository extends JpaRepository<LearningProgress, UUID> {

    Optional<LearningProgress> findByLessonIdAndStudentId(UUID lessonId, UUID studentId);

    List<LearningProgress> findByLessonId(UUID lessonId);

    List<LearningProgress> findByStudentId(UUID studentId);

    /**
     * Version-checked conditional UPDATE, deliberately not a load-then-{@code save()} —
     * same rationale as attendance's {@code correctIfVersionMatches} (Day 9):
     * sharing a Hibernate-managed entity across the caller's context and a
     * {@code REQUIRES_NEW} guard silently breaks. Returns rows updated: 1 on
     * success, 0 if {@code expectedVersion} no longer matches (a genuine
     * concurrent write, not an error by itself).
     */
    @Modifying
    @Query("update LearningProgress p set p.state = :state, p.percent = :percent, p.lastPosition = :lastPosition, "
        + "p.startedAt = :startedAt, p.completedAt = :completedAt, p.version = p.version + 1 "
        + "where p.id = :id and p.version = :expectedVersion")
    int updateIfVersionMatches(@Param("id") UUID id, @Param("state") LearningProgressState state,
                                @Param("percent") BigDecimal percent, @Param("lastPosition") String lastPosition,
                                @Param("startedAt") Instant startedAt, @Param("completedAt") Instant completedAt,
                                @Param("expectedVersion") int expectedVersion);
}
