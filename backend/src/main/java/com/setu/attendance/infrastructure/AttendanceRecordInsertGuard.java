package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.AttendanceRecord;
import com.setu.attendance.domain.AttendanceRecordStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A duplicate-insert attempt against {@code uq_attendance_records_session_enrollment}
 * doesn't just fail its own statement — in PostgreSQL, once one statement in
 * a transaction errors, the WHOLE transaction is aborted and every
 * subsequent statement fails with "current transaction is aborted" until
 * rollback (the same lesson Day 7/8 already hit). A follow-up "read the
 * winning row" query in the SAME transaction as the failed insert would
 * therefore also fail.
 * <p>
 * {@code PROPAGATION_NESTED} (a real SAVEPOINT) would be the textbook fix,
 * but Spring's Hibernate {@code JpaDialect} does not implement savepoint
 * support (confirmed: {@code NestedTransactionNotSupportedException:
 * JpaDialect does not support savepoints}). {@code PROPAGATION_REQUIRES_NEW}
 * is the working alternative: the insert runs on a genuinely separate
 * connection/transaction, so a conflict there can never poison the
 * caller's own transaction — PROVIDED the resulting exception is allowed
 * to propagate out of this method rather than being caught here. Catching
 * it internally and returning normally was tried and does NOT work: the
 * underlying Postgres transaction is still aborted regardless of whether
 * the Java exception was caught, so Spring's attempt to COMMIT this
 * REQUIRES_NEW transaction (since the method "succeeded") itself fails
 * with {@code UnexpectedRollbackException} — thrown by the transactional
 * proxy AFTER this method already returned, too late for a try-catch
 * inside the method body to help. Letting the original exception escape
 * instead makes Spring roll back this transaction cleanly and re-throw the
 * SAME original exception to the caller (a different, still-healthy
 * transaction) — which is what {@code scan()}/{@code close()} catch.
 */
@Component
public class AttendanceRecordInsertGuard {

    private final AttendanceRecordRepository attendanceRecordRepository;

    public AttendanceRecordInsertGuard(AttendanceRecordRepository attendanceRecordRepository) {
        this.attendanceRecordRepository = attendanceRecordRepository;
    }

    /** Throws (does not catch) on a constraint violation — see class javadoc for why the caller must catch it, not this method. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AttendanceRecord record) {
        attendanceRecordRepository.saveAndFlush(record);
    }

    /**
     * Same isolation, for the {@code correct()} update path
     * (transaction-concurrency-matrix.md scenario 4). Deliberately a
     * version-checked conditional UPDATE via the repository (primitive
     * values in, boolean out), NOT a load-then-{@code save()} of a
     * Hibernate-managed entity: passing an entity the CALLER already
     * loaded (and mutated) into a REQUIRES_NEW method does not fully
     * isolate it — the caller's own persistence context still tracks that
     * same object as dirty and tries to re-flush it at the CALLER's
     * commit too, hitting the version conflict a second time, uncatchable
     * in the caller's method body. Re-reading a fresh copy here instead
     * would dodge the conflict entirely (it would just see whatever is
     * currently there and always "succeed"), silently defeating the
     * optimistic check the caller's ORIGINAL read was for — so the
     * caller's originally-read {@code expectedVersion} is checked
     * explicitly against the database's current value in one atomic
     * statement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean update(UUID recordId, AttendanceRecordStatus newStatus, UUID correctedBy, int expectedVersion) {
        return attendanceRecordRepository.correctIfVersionMatches(recordId, newStatus, correctedBy, expectedVersion) == 1;
    }
}
