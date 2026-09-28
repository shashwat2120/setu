package com.setu.career.domain;

/**
 * Matches career.applications.status (V46, plain TEXT default 'SUBMITTED',
 * no DB CHECK constraint). No canonical state-machines.md entry exists for
 * this entity (confirmed absent — grepping state-machines.md for
 * career/application terms finds nothing besides an unrelated Graduation
 * Application heading). This is this module's own minimal, disclosed
 * design, following the same shape every other numbered entry in that doc
 * uses: SUBMITTED is the only entry state; SHORTLISTED and REJECTED are
 * placement-officer decisions; SELECTED and REJECTED are terminal;
 * WITHDRAWN is student-initiated and terminal, reachable from SUBMITTED or
 * SHORTLISTED only (never after a final SELECTED/REJECTED decision).
 */
public enum ApplicationStatus {
    SUBMITTED, SHORTLISTED, SELECTED, REJECTED, WITHDRAWN
}
