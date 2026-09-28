package com.setu.enrollment.domain;

/**
 * Maps enrollment.waitlist_entries.status (V33 CHECK). Full canonical
 * vocabulary from docs/state-machines.md #12, including OFFERED/EXPIRED —
 * kept for schema fidelity even though this Day 8 implementation's
 * promotion flow only ever produces WAITING -&gt; CONVERTED directly
 * (a disclosed simplification of the full time-boxed-offer-window design;
 * see WaitlistService's javadoc).
 */
public enum WaitlistStatus {
    WAITING, OFFERED, EXPIRED, CONVERTED, WITHDRAWN
}
