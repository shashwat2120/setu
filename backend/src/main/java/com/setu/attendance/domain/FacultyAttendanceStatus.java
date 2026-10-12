package com.setu.attendance.domain;

/** Matches attendance.faculty_attendance.status's DB CHECK (V73). There is deliberately no LATE: no shift schedule exists, so lateness is not computed. */
public enum FacultyAttendanceStatus {
    PRESENT, HALF_DAY, ABSENT, ON_LEAVE;

    /** Only these carry check-in/check-out times; ABSENT and ON_LEAVE rows must have none (DB CHECK). */
    public boolean hasWorkTimes() {
        return this == PRESENT || this == HALF_DAY;
    }
}
