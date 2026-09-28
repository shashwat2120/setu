package com.setu.assessment.domain;

/** Matches assessment.assessment_attempts.status's CHECK constraint (V40) exactly — see docs/state-machines.md #18. */
public enum AssessmentAttemptStatus {
    NOT_STARTED, IN_PROGRESS, SUBMITTED, EVALUATED, VOIDED
}
