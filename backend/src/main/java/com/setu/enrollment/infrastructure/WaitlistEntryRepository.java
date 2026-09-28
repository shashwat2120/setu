package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.WaitlistEntry;
import com.setu.enrollment.domain.WaitlistStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    Optional<WaitlistEntry> findBySectionIdAndStudentId(UUID sectionId, UUID studentId);

    List<WaitlistEntry> findBySectionIdAndStatusOrderByPositionAsc(UUID sectionId, WaitlistStatus status);

    List<WaitlistEntry> findBySectionIdAndStatusAndPositionGreaterThan(UUID sectionId, WaitlistStatus status, int position);

    @Query("select coalesce(max(w.position), 0) from WaitlistEntry w where w.sectionId = :sectionId and w.status = 'WAITING'")
    int findMaxWaitingPosition(@Param("sectionId") UUID sectionId);

    /**
     * transaction-concurrency-matrix.md scenario 2: {@code SELECT ... FOR
     * UPDATE SKIP LOCKED} so two concurrent drops promoting "the next"
     * waitlisted student each get a DIFFERENT entry instead of one blocking
     * on the other and silently promoting nobody. Native query — Spring
     * Data JPA's {@code @Lock} annotation has no portable SKIP LOCKED mode.
     */
    @Query(value = "SELECT * FROM enrollment.waitlist_entries WHERE section_id = :sectionId AND status = 'WAITING' "
        + "ORDER BY position ASC LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<WaitlistEntry> findNextWaitingForUpdateSkipLocked(@Param("sectionId") UUID sectionId);
}
