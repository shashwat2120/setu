package com.setu.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Maps attendance.staff_attendance_policies (V75): one immutable VERSION of a college's staff-attendance rules. A change is
 * always a new row with the next version number — rows are never edited — so each attendance record can cite the exact version it
 * was judged under. The unique {@code (tenant_id, version)} is what stops two administrators from saving the same version.
 */
@Entity
@Table(name = "staff_attendance_policies", schema = "attendance")
public class StaffAttendancePolicy {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private int version;

    /** Minutes since midnight, college-local — see V75 for why this is an integer and not a TIME column. */
    @Column(name = "shift_start_minute", nullable = false, updatable = false)
    private int shiftStartMinute;

    @Column(name = "shift_end_minute", nullable = false, updatable = false)
    private int shiftEndMinute;

    @Column(name = "grace_minutes", nullable = false, updatable = false)
    private int graceMinutes;

    @Column(name = "full_day_minutes", nullable = false, updatable = false)
    private int fullDayMinutes;

    @Column(name = "half_day_minutes", nullable = false, updatable = false)
    private int halfDayMinutes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StaffAttendancePolicy() {
    }

    /** Validation lives in {@link PolicyRules}; building the rules first means an invalid policy can never be constructed. */
    public StaffAttendancePolicy(UUID tenantId, PolicyRules rules, UUID createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.version = rules.version();
        this.shiftStartMinute = rules.shiftStart().toSecondOfDay() / 60;
        this.shiftEndMinute = rules.shiftEnd().toSecondOfDay() / 60;
        this.graceMinutes = rules.graceMinutes();
        this.fullDayMinutes = rules.fullDayMinutes();
        this.halfDayMinutes = rules.halfDayMinutes();
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public PolicyRules rules() {
        return new PolicyRules(version, LocalTime.ofSecondOfDay(shiftStartMinute * 60L), LocalTime.ofSecondOfDay(shiftEndMinute * 60L),
            graceMinutes, fullDayMinutes, halfDayMinutes);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public int version() {
        return version;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
