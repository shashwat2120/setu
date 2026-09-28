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
}
