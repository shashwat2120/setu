package com.setu.enrollment.domain;

/** Matches enrollment.course_sections.status's CHECK constraint (V28) exactly — see docs/state-machines.md #10. */
public enum CourseSectionStatus {
    DRAFT, OPEN, CLOSED, CANCELLED
}
