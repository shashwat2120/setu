package com.setu.directory.domain;

/** Matches directory.faculty_profiles.status's CHECK constraint (V14). No dedicated docs/state-machines.md entry exists for Faculty — modeled analogously to Student's shape (same near-identical status vocabulary), disclosed in the Day 6 report rather than invented silently. */
public enum FacultyStatus {
    ACTIVE, ON_LEAVE, SUSPENDED, TERMINATED
}
