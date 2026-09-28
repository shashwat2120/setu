package com.setu.career.domain;

/** Matches career.jobs.status (V46, plain TEXT default 'OPEN', no DB CHECK constraint — validated at the domain layer instead). No canonical state-machines.md entry exists for this entity; this two-state design is this module's own minimal, disclosed model. */
public enum JobStatus {
    OPEN, CLOSED
}
