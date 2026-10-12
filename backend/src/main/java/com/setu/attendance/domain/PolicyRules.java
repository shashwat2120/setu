package com.setu.attendance.domain;

import java.time.LocalTime;

/**
 * A college's staff-attendance rules at one policy version, plus the pure arithmetic that applies them. Deliberately free of
 * any clock, database or Spring dependency: given times in, it returns numbers and a status out, so every boundary can be unit
 * tested exactly. The college timezone is applied by the caller; everything here is college-local wall-clock time.
 * <p>
 * {@link #defaults()} (version 0, never stored) is what a college without a saved policy gets: shift 09:00-17:00, 15 minutes'
 * grace, 480 minutes (8h) for a full day and 240 (4h) for a half day.
 */
public record PolicyRules(int version, LocalTime shiftStart, LocalTime shiftEnd, int graceMinutes,
                          int fullDayMinutes, int halfDayMinutes) {

    public static final int DEFAULT_VERSION = 0;

    public PolicyRules {
        if (shiftStart == null || shiftEnd == null || !shiftStart.isBefore(shiftEnd)) {
            throw new IllegalArgumentException("the shift must start before it ends");
        }
        if (graceMinutes < 0 || graceMinutes > 120) {
            throw new IllegalArgumentException("grace must be between 0 and 120 minutes");
        }
        if (halfDayMinutes <= 0 || halfDayMinutes >= fullDayMinutes || fullDayMinutes > 1440) {
            throw new IllegalArgumentException("minutes for a half day must be above 0 and below a full day, which is at most 1440");
        }
    }

    public static PolicyRules defaults() {
        return new PolicyRules(DEFAULT_VERSION, LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 480, 240);
    }

    public boolean isDefault() {
        return version == DEFAULT_VERSION;
    }

    /**
     * Minutes after the shift start that someone checked in, but only when that is MORE than the grace period (so with 15
     * minutes' grace, 09:15 is on time and 09:16 is 16 minutes late); 0 when on time or early.
     */
    public int lateMinutes(LocalTime checkInLocal) {
        long after = java.time.Duration.between(shiftStart, checkInLocal).toMinutes();
        return after > graceMinutes ? (int) after : 0;
    }

    /** PRESENT for a full day's hours or more, otherwise HALF_DAY. */
    public FacultyAttendanceStatus statusForWorkedMinutes(long workedMinutes) {
        return workedMinutes >= fullDayMinutes ? FacultyAttendanceStatus.PRESENT : FacultyAttendanceStatus.HALF_DAY;
    }

    /** Fewer minutes than even a half day needs — flagged for an administrator's attention. */
    public boolean isShortHours(long workedMinutes) {
        return workedMinutes < halfDayMinutes;
    }
}
