package com.setu.identity.domain;

/** Matches identity.users.password_algo's CHECK constraint (V51). Only one value exists today by design — the locked stack names Argon2id specifically, not "a KDF of our choosing". */
public enum PasswordAlgorithm {
    ARGON2ID
}
