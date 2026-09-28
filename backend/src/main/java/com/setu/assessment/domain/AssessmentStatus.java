package com.setu.assessment.domain;

/** Matches assessment.assessments.status's CHECK constraint (V39) exactly — see docs/state-machines.md #17. */
public enum AssessmentStatus {
    DRAFT, SCHEDULED, OPEN, CLOSED, PUBLISHED, VOIDED
}
