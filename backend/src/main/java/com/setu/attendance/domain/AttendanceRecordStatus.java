package com.setu.attendance.domain;

/** Maps attendance.attendance_records.status (V36 CHECK). Exactly docs/state-machines.md #15's vocabulary. */
public enum AttendanceRecordStatus {
    PRESENT, LATE, ABSENT, EXCUSED
}
