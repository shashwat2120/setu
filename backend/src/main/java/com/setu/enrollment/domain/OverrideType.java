package com.setu.enrollment.domain;

/** Maps enrollment.enrollment_overrides.type (V33 CHECK). Exactly the four documented/deployed values — docs/state-machines.md #13 also mentions a REINSTATEMENT type that is NOT in the V33 CHECK constraint (a genuine doc/schema contradiction, disclosed in the Day 8 report, not silently added here). */
public enum OverrideType {
    PREREQUISITE, CAPACITY, CREDIT_LIMIT, HOLD
}
