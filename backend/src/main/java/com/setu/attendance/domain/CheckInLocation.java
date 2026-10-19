package com.setu.attendance.domain;

/**
 * How a check-in's reported position compared with the classroom (attendance.attendance_records.location_status). Informational only: it is
 * shown to the instructor and never decides whether a check-in counts.
 */
public enum CheckInLocation {
    /** Within the classroom radius, allowing for the accuracy the device reported. */
    NEAR,
    /** Outside the classroom radius even after allowing for the reported accuracy. */
    FAR,
    /** The student did not share a position (declined, no device support, timed out) or sent one that is not a valid position. */
    NO_LOCATION,
    /** The session has no classroom position to compare with (the instructor did not share one). */
    NO_REFERENCE
}
