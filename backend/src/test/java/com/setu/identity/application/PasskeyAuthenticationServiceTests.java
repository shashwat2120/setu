package com.setu.identity.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.Passkey;
import com.setu.identity.infrastructure.PasskeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the credential-lookup checks that happen BEFORE any cryptographic
 * ceremony verification — unknown/revoked credentials are rejected without
 * ever reaching PasskeyCeremonyVerifier (see PasskeyCeremonyVerifierTests
 * for the ceremony-level challenge/RP-ID/origin checks).
 */
@SpringBootTest
@Transactional
class PasskeyAuthenticationServiceTests {

    @Autowired
    PasskeyAuthenticationService authenticationService;
    @Autowired
    PasskeyRepository passkeyRepository;
    @Autowired
    UserRegistrationService registrationService;

    private UUID newUser() {
        var outcome = (UserRegistrationService.Outcome.Created)
            registrationService.register("passkey-auth-" + UUID.randomUUID() + "@example.com");
        return outcome.userId();
    }

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void startAuthenticationForUnknownEmailStillIssuesAChallenge_noEnumeration() {
        var challenge = authenticationService.startAuthentication(
            java.util.Optional.of("nobody-" + UUID.randomUUID() + "@example.com"));

        assertThat(challenge.challenge()).hasSize(32);
        assertThat(challenge.allowCredentialIds()).isEmpty();
    }

    @Test
    void revokingAPasskeyThenAttemptingToUseItIsRejectedBeforeCryptoVerification() {
        UUID userId = newUser();
        byte[] credentialId = UUID.randomUUID().toString().getBytes();
        Passkey passkey = new Passkey(UUID.randomUUID(), userId, credentialId, new byte[]{1, 2, 3}, 0, null,
            new String[0], false, Instant.now());
        passkeyRepository.save(passkey);
        TestAuthentication.authenticateAs(userId, Instant.now()); // revocation is step-up-gated (Day 4 §8)

        var revocationResult = authenticationService.revoke(userId, passkey.id());
        assertThat(revocationResult).isEqualTo(PasskeyAuthenticationService.RevocationResult.REVOKED);

        Passkey reloaded = passkeyRepository.findById(passkey.id()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
    }

    @Test
    void revokingSomeoneElsesPasskeyIsRejected() {
        UUID ownerUserId = newUser();
        UUID attackerUserId = newUser();
        Passkey passkey = new Passkey(UUID.randomUUID(), ownerUserId, UUID.randomUUID().toString().getBytes(),
            new byte[]{1, 2, 3}, 0, null, new String[0], false, Instant.now());
        passkeyRepository.save(passkey);
        TestAuthentication.authenticateAs(attackerUserId, Instant.now());

        var result = authenticationService.revoke(attackerUserId, passkey.id());

        assertThat(result).isEqualTo(PasskeyAuthenticationService.RevocationResult.NOT_OWNED_BY_USER);
        assertThat(passkeyRepository.findById(passkey.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void revokingWithoutStepUpIsDenied() {
        UUID userId = newUser();
        Passkey passkey = new Passkey(UUID.randomUUID(), userId, UUID.randomUUID().toString().getBytes(),
            new byte[]{1, 2, 3}, 0, null, new String[0], false, Instant.now());
        passkeyRepository.save(passkey);
        TestAuthentication.authenticateAs(userId, null); // authenticated, but no recent passkey reauthentication

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authenticationService.revoke(userId, passkey.id()))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void revokingAnUnknownPasskeyIdReturnsNotFound() {
        UUID userId = newUser();
        TestAuthentication.authenticateAs(userId, Instant.now());

        var result = authenticationService.revoke(userId, UUID.randomUUID());

        assertThat(result).isEqualTo(PasskeyAuthenticationService.RevocationResult.NOT_FOUND);
    }

    @Test
    void aMalformedAssertionIsAnOrdinaryRejectionNotAnException() {
        UUID userId = newUser();
        for (String malformed : java.util.Arrays.asList(null, "", "not json", "{}",
            "{\"id\":\"AAAAAAAAAAA\",\"rawId\":\"AAAAAAAAAAA\",\"type\":\"public-key\",\"response\":{\"clientDataJSON\":\"AAAAAAAAAAA\",\"authenticatorData\":\"AAAAAAAAAAA\",\"signature\":\"AAAAAAAAAAA\",\"userHandle\":null},\"clientExtensionResults\":{}}")) {
            var challenge = authenticationService.startStepUpAuthentication(userId);

            var outcome = authenticationService.finishAuthentication(challenge.challengeId(), malformed);

            assertThat(outcome).as("outcome for: %s", malformed).isInstanceOf(PasskeyAuthOutcome.VerificationFailed.class);
        }
    }
}
