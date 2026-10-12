package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.FacultyAttendance;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same isolation reason as {@link AttendanceRecordInsertGuard} (read its javadoc): a duplicate insert against
 * {@code uq_faculty_attendance_faculty_date} aborts the whole PostgreSQL transaction, so the insert runs in its own
 * REQUIRES_NEW transaction and lets the constraint exception escape for the caller to catch, leaving the caller's own
 * transaction healthy enough to read the row that won the race.
 */
@Component
public class FacultyAttendanceInsertGuard {

    private final FacultyAttendanceRepository repository;

    public FacultyAttendanceInsertGuard(FacultyAttendanceRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts the row and then runs {@code sameTransactionWork} (the audit row and the outbox event) in THIS inner
     * transaction, so the new record, its audit entry and its Kafka event commit or roll back together.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(FacultyAttendance row, Runnable sameTransactionWork) {
        repository.saveAndFlush(row);
        sameTransactionWork.run();
    }
}
