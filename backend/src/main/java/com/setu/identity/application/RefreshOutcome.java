package com.setu.identity.application;

/**
 * The result of attempting to redeem a refresh token. A sealed type so
 * callers (the controller) are forced to handle every case explicitly,
 * including the security-critical {@link ReuseDetected} branch — there is
 * no way to accidentally treat token reuse as a generic failure and skip
 * revoking the family.
 */
public sealed interface RefreshOutcome {

    record Rotated(TokenPair tokens) implements RefreshOutcome {
    }

    /** The token wasn't found, was already revoked (and isn't a reuse case — see below), or its session row expired. */
    record Invalid(String reason) implements RefreshOutcome {
    }

    /**
     * The presented refresh token hash matched a session that was already
     * revoked THROUGH ROTATION (not through explicit logout) — i.e. someone
     * is replaying an old, already-rotated-away token. Per
     * docs/transaction-concurrency-matrix.md scenario 7, this is treated as
     * evidence of token theft: the entire family is revoked, forcing
     * re-authentication on every device sharing that family.
     */
    record ReuseDetected(String reason) implements RefreshOutcome {
    }
}
