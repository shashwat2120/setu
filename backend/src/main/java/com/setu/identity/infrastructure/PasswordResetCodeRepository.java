package com.setu.identity.infrastructure;

import com.setu.identity.domain.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Every state change here is one atomic UPDATE with its own guard, so concurrent requests cannot both consume a code and cannot
 * spend more guesses than the cap allows (the application reads the update count: 0 rows means the guard failed).
 */
public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, UUID> {

    /** The user's newest code that is still unused and unexpired. */
    Optional<PasswordResetCode> findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);

    /** Spend one guess, only while the code is unused, unexpired and under the cap. Returns 1 if a guess was available, 0 otherwise. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetCode c set c.attempts = c.attempts + 1 "
        + "where c.id = :id and c.usedAt is null and c.expiresAt > :now and c.attempts < :max")
    int recordAttempt(@Param("id") UUID id, @Param("now") Instant now, @Param("max") int max);

    /** Mark a code used, once. Returns 1 for the single caller that wins, 0 for anyone else. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetCode c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /** Kill every unused code of a user (a new request replaces older codes; a completed reset ends all of them). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetCode c set c.usedAt = :now where c.userId = :userId and c.usedAt is null")
    int invalidateAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
