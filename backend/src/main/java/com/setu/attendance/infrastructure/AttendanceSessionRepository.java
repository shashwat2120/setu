package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.AttendanceSession;
import com.setu.attendance.domain.AttendanceSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, UUID> {

    Optional<AttendanceSession> findByMeetingIdAndStatus(UUID meetingId, AttendanceSessionStatus status);

    /** Day 16 §college-admin: every session ever opened for a section, for the admin Attendance screen. */
    List<AttendanceSession> findBySectionIdOrderByStartedAtDesc(UUID sectionId);

    /**
     * Row-lock the session for the duration of a scan/rotate/close
     * transaction — serializes concurrent scans against the same session's
     * token/status, the same {@code SELECT ... FOR UPDATE} strategy Day 8
     * established for course_sections (transaction-concurrency-matrix.md).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AttendanceSession s where s.id = :id")
    Optional<AttendanceSession> findByIdForUpdate(@Param("id") UUID id);
}
