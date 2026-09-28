package com.setu.identity.infrastructure.security;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the REAL webauthn4j ceremony verification (RP ID hash, origin,
 * challenge match) using {@code webauthn4j-test}'s virtual authenticator —
 * not mocks. Registration options/response JSON matches what a real browser
 * sends, so PasskeyCeremonyVerifier's public JSON-string entry point is
 * tested end-to-end, the same path the web controllers actually call.
 */
class PasskeyCeremonyVerifierTests {

    private static final String LEGITIMATE_RP_ID = "setu.app";
    private static final String LEGITIMATE_ORIGIN = "https://setu.app";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ObjectConverter objectConverter = new ObjectConverter();

    private byte[] randomChallenge() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private String registerAndGetResponseJson(String rpId, String origin, byte[] challenge) {
        var authenticator = new NoneAttestationAuthenticator();
        var adaptor = new WebAuthnAuthenticatorAdaptor(authenticator);
        var clientPlatform = new ClientPlatform(Origin.create(origin), adaptor);

        var creationOptions = new PublicKeyCredentialCreationOptions(
            new PublicKeyCredentialRpEntity(rpId, "Setu"),
            new PublicKeyCredentialUserEntity(UUID.randomUUID().toString().getBytes(), "user@example.com", "Test User"),
            new DefaultChallenge(challenge),
            List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
            60_000L,
            List.of(),
            new AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.DISCOURAGED, UserVerificationRequirement.PREFERRED),
            List.of(),
            AttestationConveyancePreference.NONE,
            List.of(),
            null);

        PublicKeyCredential<?, ?> credential = clientPlatform.create(creationOptions);
        return objectConverter.getJsonConverter().writeValueAsString(credential);
    }

    private WebAuthnProperties propertiesFor(String rpId, String... origins) {
        WebAuthnProperties properties = new WebAuthnProperties();
        properties.setRpId(rpId);
        properties.setAllowedOrigins(List.of(origins));
        return properties;
    }

    @Test
    void validRegistrationCeremonyVerifiesSuccessfully() {
        byte[] challenge = randomChallenge();
        String responseJson = registerAndGetResponseJson(LEGITIMATE_RP_ID, LEGITIMATE_ORIGIN, challenge);
        var verifier = new PasskeyCeremonyVerifier(propertiesFor(LEGITIMATE_RP_ID, LEGITIMATE_ORIGIN));

        var result = verifier.verifyRegistration(responseJson, challenge);

        assertThat(result.credentialId()).isNotEmpty();
        assertThat(result.publicKeyCose()).isNotEmpty();
    }

    @Test
    void invalidChallengeIsRejected() {
        byte[] realChallenge = randomChallenge();
        byte[] differentChallenge = randomChallenge();
        String responseJson = registerAndGetResponseJson(LEGITIMATE_RP_ID, LEGITIMATE_ORIGIN, realChallenge);
        var verifier = new PasskeyCeremonyVerifier(propertiesFor(LEGITIMATE_RP_ID, LEGITIMATE_ORIGIN));

        assertThatThrownBy(() -> verifier.verifyRegistration(responseJson, differentChallenge))
            .isInstanceOf(PasskeyVerificationException.class);
    }

    @Test
    void wrongRpIdIsRejected() {
        byte[] challenge = randomChallenge();
        // The client/authenticator ceremony ran for "evil.com" — its authenticatorData's
        // rpIdHash is SHA-256("evil.com"), which will never match what the server computes.
        String responseJson = registerAndGetResponseJson("evil.com", "https://evil.com", challenge);
        var verifier = new PasskeyCeremonyVerifier(propertiesFor(LEGITIMATE_RP_ID, LEGITIMATE_ORIGIN));

        assertThatThrownBy(() -> verifier.verifyRegistration(responseJson, challenge))
            .isInstanceOf(PasskeyVerificationException.class);
    }

    @Test
    void wrongOriginIsRejected() {
        byte[] challenge = randomChallenge();
        // Same (legitimate) RP ID, but the ceremony's clientDataJSON records a
        // different origin than the server is configured to accept.
        String responseJson = registerAndGetResponseJson(LEGITIMATE_RP_ID, "https://attacker.example", challenge);
        var verifier = new PasskeyCeremonyVerifier(propertiesFor(LEGITIMATE_RP_ID, LEGITIMATE_ORIGIN));

        assertThatThrownBy(() -> verifier.verifyRegistration(responseJson, challenge))
            .isInstanceOf(PasskeyVerificationException.class);
    }
}
