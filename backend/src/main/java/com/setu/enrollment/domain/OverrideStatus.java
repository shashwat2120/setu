package com.setu.enrollment.domain;

/** Maps enrollment.enrollment_overrides.status (V33 CHECK). Exactly docs/state-machines.md #13's vocabulary. */
public enum OverrideStatus {
    ACTIVE, USED, EXPIRED, REVOKED
}
