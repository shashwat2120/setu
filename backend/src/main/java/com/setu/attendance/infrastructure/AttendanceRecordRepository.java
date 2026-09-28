package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.AttendanceRecord;
import com.setu.attendance.domain.AttendanceRecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    Optional<AttendanceRecord> findByAttendanceSessionIdAndEnrollmentId(UUID attendanceSessionId, UUID enrollmentId);

    List<AttendanceRecord> findByAttendanceSessionId(UUID attendanceSessionId);

    long countByEnrollmentIdAndStatusIn(UUID enrollmentId, Collection<AttendanceRecordStatus> statuses);

    long countByEnrollmentId(UUID enrollmentId);

    /**
     * A version-checked conditional UPDATE, deliberately NOT a
     * load-then-{@code save()} — see
     * {@code AttendanceRecordInsertGuard.update}'s javadoc for why sharing
     * a Hibernate-managed entity across two persistence contexts (the
     * caller's and a REQUIRES_NEW one) silently breaks. Returns the number
     * of rows updated: 1 on success, 0 if {@code expectedVersion} no
     * longer matches (a genuine concurrent modification, not an error by
     * itself — the caller decides what that means).
     */
    @Modifying
    @Query("update AttendanceRecord r set r.status = :status, r.correctedBy = :correctedBy, r.version = r.version + 1 "
        + "where r.id = :id and r.version = :expectedVersion")
    int correctIfVersionMatches(@Param("id") UUID id, @Param("status") AttendanceRecordStatus status,
                                 @Param("correctedBy") UUID correctedBy, @Param("expectedVersion") int expectedVersion);
}
