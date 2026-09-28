package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.AttendanceQrSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AttendanceQrSessionRepository extends JpaRepository<AttendanceQrSession, UUID> {

    @Query("select q from AttendanceQrSession q where q.attendanceSessionId = :sessionId order by q.rotationNo desc limit 1")
    Optional<AttendanceQrSession> findLatest(@Param("sessionId") UUID sessionId);

    @Query("select coalesce(max(q.rotationNo), -1) from AttendanceQrSession q where q.attendanceSessionId = :sessionId")
    int findMaxRotationNo(@Param("sessionId") UUID sessionId);
}
