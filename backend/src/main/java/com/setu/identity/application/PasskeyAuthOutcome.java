package com.setu.identity.application;

import java.util.UUID;

public sealed interface PasskeyAuthOutcome {

    record Success(UUID userId, UUID passkeyId) implements PasskeyAuthOutcome {
    }

    record ChallengeExpiredOrUnknown() implements PasskeyAuthOutcome {
    }

    record UnknownCredential() implements PasskeyAuthOutcome {
    }

    record RevokedCredential() implements PasskeyAuthOutcome {
    }

    record VerificationFailed(String message) implements PasskeyAuthOutcome {
    }

    /** The credential is valid but the account itself is SUSPENDED or DEACTIVATED — see UserAccessService. */
    record AccountNotActive(com.setu.identity.domain.UserStatus status) implements PasskeyAuthOutcome {
    }
}
