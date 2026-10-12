package com.setu.attendance.domain;

/** Matches attendance.faculty_attendance_regularisations.status's DB CHECK (V74); transitions: docs/state-machines.md #26. */
public enum RegularisationStatus {
    PENDING, APPROVED, REJECTED, WITHDRAWN
}
