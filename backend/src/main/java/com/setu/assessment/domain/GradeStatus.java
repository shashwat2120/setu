package com.setu.assessment.domain;

/** Matches assessment.grades.status's CHECK constraint (V41) exactly — see docs/state-machines.md #19. */
public enum GradeStatus {
    DRAFT, PUBLISHED, SUPERSEDED
}
