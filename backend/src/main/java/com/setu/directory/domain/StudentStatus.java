package com.setu.directory.domain;

/** Matches directory.student_profiles.status's CHECK constraint (V14) exactly — see docs/state-machines.md #7. */
public enum StudentStatus {
    ACTIVE, ON_LEAVE, SUSPENDED, GRADUATED, WITHDRAWN
}
