package com.setu.identity.infrastructure;

import com.setu.identity.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByRefreshTokenHash(String refreshTokenHash);

    List<Session> findByFamilyId(UUID familyId);

    List<Session> findByUserIdAndRevokedAtIsNull(UUID userId);

    /** True while at least one session of this login (family) is neither revoked nor expired: the login is still live. */
    @Query("SELECT COUNT(s) > 0 FROM Session s WHERE s.familyId = :familyId AND s.userId = :userId AND s.revokedAt IS NULL AND s.expiresAt > :now")
    boolean existsLiveSession(@Param("familyId") UUID familyId, @Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Reuse-detected compromise response: revoke every session descended
     * from this login in one statement. {@code clearAutomatically = true}
     * matters: a JPQL bulk UPDATE bypasses the persistence context, so
     * without it, a Session entity already loaded earlier in the same
     * transaction (e.g. the just-rotated-to row) would keep showing its
     * stale pre-update {@code revokedAt} on any later read within that same
     * transaction — clearing forces the next read to hit the database.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Session s SET s.revokedAt = :now WHERE s.familyId = :familyId AND s.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
