package com.setu.risk.domain;

/** Matches risk.interventions.status's DB CHECK constraint and state-machines.md #23 (Intervention) exactly. */
public enum InterventionStatus {
    PLANNED, IN_PROGRESS, COMPLETED, CANCELLED
}
