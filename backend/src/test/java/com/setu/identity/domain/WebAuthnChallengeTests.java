package com.setu.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebAuthnChallengeTests {

    @Test
    void unexpiredUnconsumedChallengeIsUsable() {
        Instant now = Instant.now();
        WebAuthnChallenge challenge = new WebAuthnChallenge(UUID.randomUUID(), UUID.randomUUID(), new byte[]{1},
            ChallengeType.REGISTRATION, "setu.app", now.plus(5, ChronoUnit.MINUTES), now);

        assertThat(challenge.isUsable(now)).isTrue();
    }

    @Test
    void expiredChallengeIsNotUsable() {
        Instant now = Instant.now();
        Instant issuedInThePast = now.minus(10, ChronoUnit.MINUTES);
        WebAuthnChallenge challenge = new WebAuthnChallenge(UUID.randomUUID(), UUID.randomUUID(), new byte[]{1},
            ChallengeType.REGISTRATION, "setu.app", issuedInThePast.plus(5, ChronoUnit.MINUTES), issuedInThePast);

        assertThat(challenge.isUsable(now)).isFalse();
    }

    @Test
    void consumedChallengeIsNotUsableAgain_singleUse() {
        Instant now = Instant.now();
        WebAuthnChallenge challenge = new WebAuthnChallenge(UUID.randomUUID(), UUID.randomUUID(), new byte[]{1},
            ChallengeType.AUTHENTICATION, "setu.app", now.plus(5, ChronoUnit.MINUTES), now);

        challenge.consume(now);

        assertThat(challenge.isUsable(now)).isFalse();
    }
}
