package com.setu.identity.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.infrastructure.security.WebAuthnProperties;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end passkey registration + authentication through the real service
 * layer (not just PasskeyCeremonyVerifier in isolation — see
 * PasskeyCeremonyVerifierTests for that), using a single virtual
 * authenticator whose credential store persists across both ceremonies, the
 * same way a real device's passkey would. Runs against the real Postgres.
 */
@SuppressWarnings("deprecation") // webauthn4j ObjectConverter#getJsonConverter: see PasskeyCeremonyVerifier
@SpringBootTest
@Transactional
class PasskeyFullFlowTests {

    @Autowired
    UserRegistrationService registrationService;
    @Autowired
    PasskeyRegistrationService passkeyRegistrationService;
    @Autowired
    PasskeyAuthenticationService passkeyAuthenticationService;
    @Autowired
    WebAuthnProperties webAuthnProperties;
    @Autowired
    com.setu.identity.infrastructure.UserRepository userRepository;

    private final ObjectConverter objectConverter = new ObjectConverter();

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private UUID newUser(String email) {
        var outcome = (UserRegistrationService.Outcome.Created) registrationService.register(email);
        return outcome.userId();
    }

    private String registrationResponseJson(NoneAttestationAuthenticator authenticator, String rpId, String origin,
                                             byte[] challenge, byte[] userHandle) {
        var adaptor = new WebAuthnAuthenticatorAdaptor(authenticator);
        var clientPlatform = new ClientPlatform(Origin.create(origin), adaptor);
        var creationOptions = new PublicKeyCredentialCreationOptions(
            new PublicKeyCredentialRpEntity(rpId, "Setu"),
            new PublicKeyCredentialUserEntity(userHandle, "user@example.com", "Test User"),
            new DefaultChallenge(challenge),
            List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
            60_000L, List.of(),
            // REQUIRED: a "passkey" is specifically a discoverable/resident
            // credential — that's what makes the usernameless authentication
            // flow (PasskeyAuthenticationService.startAuthentication with no
            // email) work at all, so tests exercise that real behavior.
            new AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.PREFERRED),
            List.of(), AttestationConveyancePreference.NONE, List.of(), null);
        PublicKeyCredential<?, ?> credential = clientPlatform.create(creationOptions);
        return objectConverter.getJsonConverter().writeValueAsString(credential);
    }

    private String authenticationResponseJson(NoneAttestationAuthenticator authenticator, String rpId, String origin,
                                                byte[] challenge) {
        var adaptor = new WebAuthnAuthenticatorAdaptor(authenticator);
        var clientPlatform = new ClientPlatform(Origin.create(origin), adaptor);
        var requestOptions = new PublicKeyCredentialRequestOptions(
            new DefaultChallenge(challenge), 60_000L, rpId, List.of(), UserVerificationRequirement.PREFERRED, null);
        PublicKeyCredential<?, ?> credential = clientPlatform.get(requestOptions);
        return objectConverter.getJsonConverter().writeValueAsString(credential);
    }

    @Test
    void registerThenAuthenticateSucceeds_fullRoundTrip() {
        String rpId = webAuthnProperties.getRpId();
        String origin = webAuthnProperties.getAllowedOrigins().get(0);
        UUID userId = newUser("full-flow-" + UUID.randomUUID() + "@example.com");
        var authenticator = new NoneAttestationAuthenticator();

        var startResult = (PasskeyRegistrationService.StartRegistrationResult.Started)
            passkeyRegistrationService.startRegistration(userId, Optional.empty());
        String regJson = registrationResponseJson(authenticator, rpId, origin, startResult.challenge().challenge(),
            startResult.challenge().userHandle());
        var regOutcome = passkeyRegistrationService.finishRegistration(
            userId, Optional.empty(), startResult.challenge().challengeId(), regJson);
        assertThat(regOutcome).isInstanceOf(RegistrationOutcome.Success.class);

        var authChallenge = passkeyAuthenticationService.startAuthentication(Optional.empty());
        String authJson = authenticationResponseJson(authenticator, rpId, origin, authChallenge.challenge());
        var authOutcome = passkeyAuthenticationService.finishAuthentication(authChallenge.challengeId(), authJson);

        assertThat(authOutcome).isInstanceOf(PasskeyAuthOutcome.Success.class);
        assertThat(((PasskeyAuthOutcome.Success) authOutcome).userId()).isEqualTo(userId);
    }

    @Test
    void authenticatingWithAnUnregisteredCredentialIsRejected_unknownCredential() {
        String rpId = webAuthnProperties.getRpId();
        String origin = webAuthnProperties.getAllowedOrigins().get(0);
        // This authenticator's credential was never registered with the server.
        var strayAuthenticator = new NoneAttestationAuthenticator();
        UUID someUser = newUser("unknown-cred-" + UUID.randomUUID() + "@example.com");
        String regJson = registrationResponseJson(strayAuthenticator, rpId, origin, new byte[32],
            PasskeyRegistrationService.userHandleFor(someUser));
        // Register it against webauthn4j directly is skipped — we only need
        // the authenticator to hold a credential so it can produce a signed
        // assertion; the server never learns about it via finishRegistration.

        var authChallenge = passkeyAuthenticationService.startAuthentication(Optional.empty());
        String authJson = authenticationResponseJson(strayAuthenticator, rpId, origin, authChallenge.challenge());

        var outcome = passkeyAuthenticationService.finishAuthentication(authChallenge.challengeId(), authJson);

        assertThat(outcome).isInstanceOf(PasskeyAuthOutcome.UnknownCredential.class);
    }

    @Test
    void authenticatingWithARevokedCredentialIsRejected() {
        String rpId = webAuthnProperties.getRpId();
        String origin = webAuthnProperties.getAllowedOrigins().get(0);
        UUID userId = newUser("revoked-cred-" + UUID.randomUUID() + "@example.com");
        var authenticator = new NoneAttestationAuthenticator();

        var startResult = (PasskeyRegistrationService.StartRegistrationResult.Started)
            passkeyRegistrationService.startRegistration(userId, Optional.empty());
        String regJson = registrationResponseJson(authenticator, rpId, origin, startResult.challenge().challenge(),
            startResult.challenge().userHandle());
        var regOutcome = (RegistrationOutcome.Success)
            passkeyRegistrationService.finishRegistration(userId, Optional.empty(), startResult.challenge().challengeId(), regJson);

        TestAuthentication.authenticateAs(userId, java.time.Instant.now());
        passkeyAuthenticationService.revoke(userId, regOutcome.passkeyId());

        var authChallenge = passkeyAuthenticationService.startAuthentication(Optional.empty());
        String authJson = authenticationResponseJson(authenticator, rpId, origin, authChallenge.challenge());
        var outcome = passkeyAuthenticationService.finishAuthentication(authChallenge.challengeId(), authJson);

        assertThat(outcome).isInstanceOf(PasskeyAuthOutcome.RevokedCredential.class);
    }

    @Test
    void authenticatingWithAValidPasskeyOnASuspendedAccountIsRejected() {
        String rpId = webAuthnProperties.getRpId();
        String origin = webAuthnProperties.getAllowedOrigins().get(0);
        UUID userId = newUser("suspended-account-passkey-" + UUID.randomUUID() + "@example.com");
        var authenticator = new NoneAttestationAuthenticator();

        var startResult = (PasskeyRegistrationService.StartRegistrationResult.Started)
            passkeyRegistrationService.startRegistration(userId, Optional.empty());
        String regJson = registrationResponseJson(authenticator, rpId, origin, startResult.challenge().challenge(),
            startResult.challenge().userHandle());
        var regOutcome = passkeyRegistrationService.finishRegistration(
            userId, Optional.empty(), startResult.challenge().challengeId(), regJson);
        assertThat(regOutcome).isInstanceOf(RegistrationOutcome.Success.class);

        var user = userRepository.findById(userId).orElseThrow();
        user.suspend();
        userRepository.save(user);

        var authChallenge = passkeyAuthenticationService.startAuthentication(Optional.empty());
        String authJson = authenticationResponseJson(authenticator, rpId, origin, authChallenge.challenge());
        var outcome = passkeyAuthenticationService.finishAuthentication(authChallenge.challengeId(), authJson);

        assertThat(outcome).isInstanceOf(PasskeyAuthOutcome.AccountNotActive.class);
        assertThat(((PasskeyAuthOutcome.AccountNotActive) outcome).status()).isEqualTo(com.setu.identity.domain.UserStatus.SUSPENDED);
    }
}
