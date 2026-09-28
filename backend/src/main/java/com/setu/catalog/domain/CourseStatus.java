package com.setu.catalog.domain;

/** Matches catalog.courses.status's CHECK constraint (V21) exactly — see docs/state-machines.md #9. */
public enum CourseStatus {
    DRAFT, ACTIVE, DEPRECATED, RETIRED
}
