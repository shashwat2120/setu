/**
 * Attendance module.
 * <p>
 * QR attendance session lifecycle and attendance records, the source of truth for attendance percentage. Owns the attendance schema.
 * Backed by the PostgreSQL 'attendance' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.attendance.application} services, never through
 * {@code com.setu.attendance.domain} or {@code com.setu.attendance.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Attendance"
)
package com.setu.attendance;
