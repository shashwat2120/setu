package com.setu.enrollment.domain;

/** Matches enrollment.student_programs.status's CHECK constraint (V31) exactly — see docs/state-machines.md #8. */
public enum StudentProgramStatus {
    ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED
}
