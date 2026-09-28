package com.setu.identity.application;

import java.util.UUID;

public sealed interface RegistrationOutcome {

    record Success(UUID passkeyId) implements RegistrationOutcome {
    }

    record ChallengeExpiredOrUnknown() implements RegistrationOutcome {
    }

    /** The caller isn't authenticated as this user, and this user already has at least one credential — adding a passkey to an existing account requires proving you already control it. */
    record NotAuthorized() implements RegistrationOutcome {
    }

    /** DB-level uq_passkeys_credential_id backs this too — this is the application-level check that gives a clean result instead of a constraint-violation exception. */
    record CredentialAlreadyRegistered() implements RegistrationOutcome {
    }

    record VerificationFailed(String message) implements RegistrationOutcome {
    }
}
