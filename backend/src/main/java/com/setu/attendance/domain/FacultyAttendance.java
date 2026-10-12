package com.setu.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps attendance.faculty_attendance (V73): one faculty member's own attendance for one calendar day (in the college's
 * timezone). State machine and rules: docs/state-machines.md #25. {@code (faculty_id, attendance_date)} is unique at the
 * database level, so two concurrent check-ins cannot both insert; {@code version} guards a check-out racing an admin
 * correction. Whether the caller MAY do any of this is decided by the service, never here.
 */
@Entity
@Table(name = "faculty_attendance", schema = "attendance")
public class FacultyAttendance {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "faculty_id", nullable = false)
    private UUID facultyId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FacultyAttendanceStatus status;

    @Column(name = "check_in_at")
    private Instant checkInAt;

    @Column(name = "check_out_at")
    private Instant checkOutAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FacultyAttendanceSource source;

    @Column(name = "corrected_by")
    private UUID correctedBy;

    @Column(name = "correction_reason")
    private String correctionReason;

    @Column(name = "late_minutes")
    private Integer lateMinutes;

    @Column(name = "worked_minutes")
    private Integer workedMinutes;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Column(name = "short_hours", nullable = false)
    private boolean shortHours;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected FacultyAttendance() {
    }

    private FacultyAttendance(UUID id, UUID tenantId, UUID facultyId, LocalDate attendanceDate,
                               FacultyAttendanceStatus status, Instant checkInAt, FacultyAttendanceSource source,
                               UUID correctedBy, String correctionReason, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.facultyId = facultyId;
        this.attendanceDate = attendanceDate;
        this.status = status;
        this.checkInAt = checkInAt;
        this.source = source;
        this.correctedBy = correctedBy;
        this.correctionReason = correctionReason;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The faculty member's own check-in: PRESENT, checked in now, not yet out. */
    public static FacultyAttendance checkIn(UUID tenantId, UUID facultyId, LocalDate date, Instant now) {
        return new FacultyAttendance(UUID.randomUUID(), tenantId, facultyId, date, FacultyAttendanceStatus.PRESENT, now,
            FacultyAttendanceSource.SELF, null, null, now);
    }

    /** An administrator creating the row for a day with no record yet; carries no work times (none were observed). */
    public static FacultyAttendance markedByAdmin(UUID tenantId, UUID facultyId, LocalDate date,
                                                   FacultyAttendanceStatus status, UUID adminUserId, String reason, Instant now) {
        requireReason(reason);
        return new FacultyAttendance(UUID.randomUUID(), tenantId, facultyId, date, status, null,
            FacultyAttendanceSource.ADMIN, adminUserId, reason.trim(), now);
    }

    /** Same as {@link #markedByAdmin} but with the check-in/out times an approved regularisation asked for (only meaningful for PRESENT/HALF_DAY). */
    public static FacultyAttendance regularised(UUID tenantId, UUID facultyId, LocalDate date, FacultyAttendanceStatus status,
                                                 Instant checkInAt, Instant checkOutAt, UUID adminUserId, String reason, Instant now) {
        requireReason(reason);
        validateTimes(status, checkInAt, checkOutAt);
        var row = new FacultyAttendance(UUID.randomUUID(), tenantId, facultyId, date, status, status.hasWorkTimes() ? checkInAt : null,
            FacultyAttendanceSource.ADMIN, adminUserId, reason.trim(), now);
        row.checkOutAt = status.hasWorkTimes() ? checkOutAt : null;
        return row;
    }

    public boolean isCheckedIn() {
        return checkInAt != null;
    }

    public boolean isCheckedOut() {
        return checkOutAt != null;
    }

    /** Only after a check-in, only once, never before the check-in itself. */
    public void checkOut(Instant now) {
        if (!isCheckedIn()) {
            throw new IllegalStateException("cannot check out before checking in");
        }
        if (isCheckedOut()) {
            throw new IllegalStateException("already checked out");
        }
        this.checkOutAt = now.isBefore(checkInAt) ? checkInAt : now;
        this.updatedAt = now;
    }

    /**
     * An administrator's correction of this day. Moving to ABSENT/ON_LEAVE clears the work times (the DB forbids them on
     * those statuses); the caller's audit row keeps the old values. Always records who and why.
     */
    public void correctByAdmin(FacultyAttendanceStatus newStatus, UUID adminUserId, String reason, Instant now) {
        requireReason(reason);
        this.status = newStatus;
        if (!newStatus.hasWorkTimes()) {
            this.checkInAt = null;
            this.checkOutAt = null;
            clearMetrics();
        }
        this.source = FacultyAttendanceSource.ADMIN;
        this.correctedBy = adminUserId;
        this.correctionReason = reason.trim();
        this.updatedAt = now;
    }

    /**
     * An approved regularisation: like {@link #correctByAdmin(FacultyAttendanceStatus, UUID, String, Instant)} but applying the
     * requested times. For PRESENT/HALF_DAY a requested time replaces the existing one; a time the request did not give is kept.
     */
    public void regularise(FacultyAttendanceStatus newStatus, Instant checkInAt, Instant checkOutAt, UUID adminUserId, String reason, Instant now) {
        requireReason(reason);
        Instant in = checkInAt != null ? checkInAt : this.checkInAt;
        Instant out = checkOutAt != null ? checkOutAt : this.checkOutAt;
        correctByAdmin(newStatus, adminUserId, reason, now);
        if (newStatus.hasWorkTimes()) {
            validateTimes(newStatus, in, out);
            this.checkInAt = in;
            this.checkOutAt = out;
        }
    }

    /**
     * Computes this day's policy-derived metrics from the times it carries, under {@code rules} and the college timezone: late minutes
     * (only beyond the grace period), worked minutes (needs both times), the short-hours flag, and which policy version judged it.
     * Safe to call again after any change to the times; a day with no work times (ABSENT / ON_LEAVE) has no metrics.
     */
    public void applyPolicy(PolicyRules rules, java.time.ZoneId zone) {
        if (!status.hasWorkTimes() || checkInAt == null) {
            clearMetrics();
            return;
        }
        int late = rules.lateMinutes(checkInAt.atZone(zone).toLocalTime());
        this.lateMinutes = late > 0 ? late : null;
        if (checkOutAt != null) {
            long worked = java.time.Duration.between(checkInAt, checkOutAt).toMinutes();
            this.workedMinutes = (int) worked;
            this.shortHours = rules.isShortHours(worked);
        } else {
            this.workedMinutes = null;
            this.shortHours = false;
        }
        this.policyVersion = rules.version();
    }

    /**
     * The automatic outcome of a SELF check-out (only a row whose status the faculty member's own check-in set): a PRESENT day on which fewer than the policy's full-day minutes were worked becomes
     * HALF_DAY. Anything an administrator set (or any other status) is left exactly as it is — a person decided that.
     */
    public void applySelfCheckOutOutcome(PolicyRules rules) {
        if (source == FacultyAttendanceSource.SELF && status == FacultyAttendanceStatus.PRESENT && workedMinutes != null
            && rules.statusForWorkedMinutes(workedMinutes) == FacultyAttendanceStatus.HALF_DAY) {
            this.status = FacultyAttendanceStatus.HALF_DAY;
        }
    }

    private void clearMetrics() {
        this.lateMinutes = null;
        this.workedMinutes = null;
        this.policyVersion = null;
        this.shortHours = false;
    }

    private static void validateTimes(FacultyAttendanceStatus status, Instant in, Instant out) {
        if (!status.hasWorkTimes()) {
            return;
        }
        if (out != null && in == null) {
            throw new IllegalArgumentException("a check-out time needs a check-in time");
        }
        if (in != null && out != null && out.isBefore(in)) {
            throw new IllegalArgumentException("check-out cannot be before check-in");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID facultyId() {
        return facultyId;
    }

    public LocalDate attendanceDate() {
        return attendanceDate;
    }

    public FacultyAttendanceStatus status() {
        return status;
    }

    public Instant checkInAt() {
        return checkInAt;
    }

    public Instant checkOutAt() {
        return checkOutAt;
    }

    public FacultyAttendanceSource source() {
        return source;
    }

    public UUID correctedBy() {
        return correctedBy;
    }

    public String correctionReason() {
        return correctionReason;
    }

    public Integer lateMinutes() {
        return lateMinutes;
    }

    public Integer workedMinutes() {
        return workedMinutes;
    }

    public Integer policyVersion() {
        return policyVersion;
    }

    public boolean shortHours() {
        return shortHours;
    }

    public int version() {
        return version;
    }
}
