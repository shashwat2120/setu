package com.setu.shared.authorization;

/** The result of one authorization evaluation — a sealed type so callers can't accidentally treat an unhandled case as allowed. */
public sealed interface AccessDecision {

    record Allow() implements AccessDecision {
    }

    /** {@code reasonCode} is a stable, machine-checkable string (e.g. "tenant_mismatch", "not_owner_or_assigned") — safe to log/audit, never a human-readable sentence that might leak resource details to a denied caller. */
    record Deny(String reasonCode) implements AccessDecision {
    }

    default boolean isAllowed() {
        return this instanceof Allow;
    }
}
