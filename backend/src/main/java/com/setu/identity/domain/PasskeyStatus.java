package com.setu.identity.domain;

/** Matches identity.passkeys.status's CHECK constraint (V12) exactly. */
public enum PasskeyStatus {
    ACTIVE, REVOKED
}
