package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.AttendanceSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy "one OPEN session per meeting" INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@link AttendanceRecordInsertGuard} (this module's own established
 * precedent) and {@code AssessmentComponentInsertGuard} (whose javadoc has
 * the full explanation of why this is necessary: a failed flush aborts the
 * whole PostgreSQL transaction regardless of whether the Java exception is
 * caught, so without this isolation the caller's own later commit would
 * fail with {@code UnexpectedRollbackException} instead of returning the
 * already-correctly-typed {@code OpenResult.SessionAlreadyOpenForMeeting}).
 * <p>
 * Added Sprint 5D hardening follow-up: {@code AttendanceSessionService#open}
 * previously called {@code attendanceSessionRepository.saveAndFlush}
 * directly against its own {@code @Transactional} connection.
 */
@Component
public class AttendanceSessionInsertGuard {

    private final AttendanceSessionRepository attendanceSessionRepository;

    public AttendanceSessionInsertGuard(AttendanceSessionRepository attendanceSessionRepository) {
        this.attendanceSessionRepository = attendanceSessionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AttendanceSession session) {
        attendanceSessionRepository.saveAndFlush(session);
    }
}
