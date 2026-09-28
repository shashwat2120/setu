package com.setu.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** sign_count handling per WebAuthn §6.1.1 — a cloned authenticator produces a signature whose counter doesn't advance past what was last stored. */
class PasskeyTests {

    private Passkey newPasskey(long initialSignCount) {
        return new Passkey(UUID.randomUUID(), UUID.randomUUID(), new byte[]{1, 2, 3}, new byte[]{4, 5, 6},
            initialSignCount, null, new String[0], false, Instant.now());
    }

    @Test
    void higherSignCountIsValid() {
        Passkey passkey = newPasskey(5);
        assertThat(passkey.isSignCountValid(6)).isTrue();
    }

    @Test
    void equalOrLowerSignCountIsInvalid_possibleClonedAuthenticator() {
        Passkey passkey = newPasskey(5);
        assertThat(passkey.isSignCountValid(5)).isFalse();
        assertThat(passkey.isSignCountValid(4)).isFalse();
    }

    @Test
    void bothSidesAtZeroIsValid_authenticatorOptedOutOfCounting() {
        Passkey passkey = newPasskey(0);
        assertThat(passkey.isSignCountValid(0)).isTrue();
    }

    @Test
    void revokedPasskeyIsNoLongerActive() {
        Passkey passkey = newPasskey(0);
        assertThat(passkey.isActive()).isTrue();
        passkey.revoke(Instant.now());
        assertThat(passkey.isActive()).isFalse();
    }
}
