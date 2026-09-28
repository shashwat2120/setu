package com.setu.enrollment.domain;

/** Maps enrollment.enrollments.status (V32). Exactly docs/state-machines.md #11's vocabulary — no additional states. */
public enum EnrollmentStatus {
    REQUESTED, ENROLLED, WAITLISTED, DROPPED, COMPLETED, REJECTED
}
