package com.setu.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps attendance.attendance_records (V36) — the ONLY source for attendance
 * percentage (never a stored aggregate column, per the ERD's explicit
 * note); callers derive a percentage by counting rows, this entity never
 * computes or caches one. The composite unique
 * {@code (attendance_session_id, enrollment_id)} is the duplicate-scan
 * concurrency guard (transaction-concurrency-matrix.md scenario 3) — a
 * second scan attempt hits this constraint at the DB level, not just an
 * application check-then-insert race. Per docs/state-machines.md #15, any
 * state may move to any other state via manual correction only — this
 * entity places no transition restriction of its own; the SERVICE layer
 * enforces WHO may correct and WHEN (only after the session is CLOSED).
 */
@Entity
@Table(name = "attendance_records", schema = "attendance")
public class AttendanceRecord {

    @Id
    private UUID id;

    @Column(name = "attendance_session_id", nullable = false)
    private UUID attendanceSessionId;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(name = "corrected_by")
    private UUID correctedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceRecordStatus status;

    @Column(name = "marked_at", nullable = false)
    private Instant markedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceSource source;

    @Version
    private int version;

    protected AttendanceRecord() {
    }

    public AttendanceRecord(UUID id, UUID attendanceSessionId, UUID enrollmentId, AttendanceRecordStatus status,
                             Instant markedAt, AttendanceSource source) {
        this.id = id;
        this.attendanceSessionId = attendanceSessionId;
        this.enrollmentId = enrollmentId;
        this.status = status;
        this.markedAt = markedAt;
        this.source = source;
    }

    public UUID id() {
        return id;
    }

    public int version() {
        return version;
    }

    public UUID attendanceSessionId() {
        return attendanceSessionId;
    }

    public UUID enrollmentId() {
        return enrollmentId;
    }

    public UUID correctedBy() {
        return correctedBy;
    }

    public AttendanceRecordStatus status() {
        return status;
    }

    public Instant markedAt() {
        return markedAt;
    }

    public AttendanceSource source() {
        return source;
    }

    /** The correction mechanism itself (docs/state-machines.md #15) — updates this row rather than appending a new one, a narrow, audited exception to the append-only convention; the caller writes the accompanying audit/history row in the same transaction. */
    public void correct(AttendanceRecordStatus newStatus, UUID correctedBy) {
        this.status = newStatus;
        this.correctedBy = correctedBy;
    }
}
