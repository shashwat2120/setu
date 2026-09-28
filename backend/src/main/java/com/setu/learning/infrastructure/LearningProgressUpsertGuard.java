package com.setu.learning.infrastructure;

import com.setu.learning.domain.LearningProgress;
import com.setu.learning.domain.LearningProgressState;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Isolates the two racy learning_progress writes in their own {@code
 * REQUIRES_NEW} transactions — the same PostgreSQL "poisoned transaction"
 * defense Day 9's {@code AttendanceRecordInsertGuard} established: a
 * constraint violation aborts the whole enclosing Postgres transaction, so
 * the risky write must run on a genuinely separate connection the caller's
 * transaction never shares. Both exceptions here must be allowed to
 * PROPAGATE out of this class (never caught here) — catching inside a
 * {@code REQUIRES_NEW} method doesn't rescue it; Spring's own commit
 * attempt on the "successful" return still fails with {@code
 * UnexpectedRollbackException} after the method already returned. The
 * caller (a separate, healthy transaction) catches instead.
 */
@Component
public class LearningProgressUpsertGuard {

    private final LearningProgressRepository learningProgressRepository;

    public LearningProgressUpsertGuard(LearningProgressRepository learningProgressRepository) {
        this.learningProgressRepository = learningProgressRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(LearningProgress progress) {
        learningProgressRepository.saveAndFlush(progress);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateIfVersionMatches(UUID id, LearningProgressState state, BigDecimal percent,
                                           String lastPosition, Instant startedAt, Instant completedAt,
                                           int expectedVersion) {
        return learningProgressRepository.updateIfVersionMatches(
            id, state, percent, lastPosition, startedAt, completedAt, expectedVersion) == 1;
    }
}
