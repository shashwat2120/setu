package com.setu.identity.application;

import java.time.Instant;
import java.util.UUID;

public sealed interface PasswordAuthOutcome {

    record Success(UUID userId) implements PasswordAuthOutcome {
    }

    record InvalidCredentials() implements PasswordAuthOutcome {
    }

    record AccountLocked(Instant until) implements PasswordAuthOutcome {
    }

    /** The account has no password set (passkey-only) — not the same failure as a wrong password; the caller should say so, not imply the user mistyped something. */
    record NoPasswordConfigured() implements PasswordAuthOutcome {
    }
}
