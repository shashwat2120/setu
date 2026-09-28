package com.setu.identity.infrastructure.security;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.authenticator.Authenticator;
import com.webauthn4j.authenticator.AuthenticatorImpl;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates webauthn4j to verify one WebAuthn ceremony's cryptographic
 * integrity: RP ID hash, origin, challenge match, signature, and (for
 * authentication) sign-counter regression. Challenge existence/expiry/
 * single-use and credential existence/revocation are checked by the caller
 * BEFORE reaching here (see PasskeyRegistrationService/
 * PasskeyAuthenticationService) — this class only wraps the pure ceremony
 * math, matching webauthn4j's own scope.
 * <p>
 * Split into JSON-string entry points (what the web layer actually calls)
 * and package-visible object-based overloads underneath — the latter are
 * what the security tests exercise directly with webauthn4j-test-generated
 * ceremony data, so tests cover the real RP-ID/origin/challenge/signature
 * logic without needing to hand-build WebAuthn JSON request bodies.
 */
@Component
@EnableConfigurationProperties(WebAuthnProperties.class)
public class PasskeyCeremonyVerifier {

    private final WebAuthnManager webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final ObjectConverter objectConverter = new ObjectConverter();
    private final WebAuthnProperties properties;

    public PasskeyCeremonyVerifier(WebAuthnProperties properties) {
        this.properties = properties;
    }

    public record VerifiedRegistration(byte[] credentialId, byte[] publicKeyCose, UUID aaguid, long signCount,
                                        Set<String> transports, boolean backedUp) {
    }

    public record VerifiedAuthentication(long signCount) {
    }

    public VerifiedRegistration verifyRegistration(String registrationResponseJson, byte[] expectedChallenge) {
        RegistrationData data;
        try {
            data = webAuthnManager.parseRegistrationResponseJSON(registrationResponseJson);
        } catch (com.webauthn4j.converter.exception.DataConversionException e) {
            throw new PasskeyVerificationException("Malformed passkey registration response", e);
        }
        return verifyRegistration(data, serverProperty(expectedChallenge));
    }

    /** Package-visible for tests: verify already-parsed registration data against an explicit, fully-controlled ServerProperty (rpId/origin/challenge). */
    VerifiedRegistration verifyRegistration(RegistrationData data, ServerProperty serverProperty) {
        RegistrationParameters params = new RegistrationParameters(serverProperty, true, true);
        try {
            webAuthnManager.verify(data, params);
        } catch (VerificationException e) {
            throw new PasskeyVerificationException("Passkey registration ceremony failed verification", e);
        }
        AuthenticatorImpl authenticator = AuthenticatorImpl.createFromRegistrationData(data);
        AttestedCredentialData credentialData = authenticator.getAttestedCredentialData();
        byte[] publicKeyCose = objectConverter.getCborConverter().writeValueAsBytes(credentialData.getCOSEKey());
        UUID aaguid = credentialData.getAaguid() == null ? null : credentialData.getAaguid().getValue();
        Set<String> transports = data.getTransports() == null ? Set.of()
            : data.getTransports().stream().map(Object::toString).collect(Collectors.toSet());
        boolean backedUp = data.getAttestationObject().getAuthenticatorData().isFlagBS();
        return new VerifiedRegistration(credentialData.getCredentialId(), publicKeyCose, aaguid,
            authenticator.getCounter(), transports, backedUp);
    }

    public VerifiedAuthentication verifyAuthentication(String authenticationResponseJson, byte[] expectedChallenge,
                                                         byte[] storedCredentialId, byte[] storedPublicKeyCose,
                                                         long storedSignCount) {
        AuthenticationData parsed = parseAuthentication(authenticationResponseJson);
        Authenticator authenticator = reconstructAuthenticator(storedCredentialId, storedPublicKeyCose, storedSignCount);
        return verifyAuthentication(parsed, serverProperty(expectedChallenge), authenticator);
    }

    /** Package-visible for tests: verify already-parsed authentication data against an explicit ServerProperty and stored Authenticator. */
    VerifiedAuthentication verifyAuthentication(AuthenticationData parsed, ServerProperty serverProperty,
                                                 Authenticator authenticator) {
        AuthenticationParameters params = new AuthenticationParameters(serverProperty, authenticator, null, true, true);
        AuthenticationData verified;
        try {
            verified = webAuthnManager.verify(parsed, params);
        } catch (VerificationException e) {
            throw new PasskeyVerificationException("Passkey authentication ceremony failed verification", e);
        }
        return new VerifiedAuthentication(verified.getAuthenticatorData().getSignCount());
    }

    /** Extracted so callers can read a response's claimed credentialId/userHandle before full verification (needed to look up the stored credential). */
    public AuthenticationData parseAuthentication(String authenticationResponseJson) {
        try {
            return webAuthnManager.parseAuthenticationResponseJSON(authenticationResponseJson);
        } catch (com.webauthn4j.converter.exception.DataConversionException e) {
            throw new PasskeyVerificationException("Malformed passkey authentication response", e);
        }
    }

    Authenticator reconstructAuthenticator(byte[] storedCredentialId, byte[] storedPublicKeyCose, long storedSignCount) {
        COSEKey coseKey = objectConverter.getCborConverter().readValue(storedPublicKeyCose, COSEKey.class);
        AttestedCredentialData credentialData = new AttestedCredentialData(AAGUID.ZERO, storedCredentialId, coseKey);
        return new AuthenticatorImpl(credentialData, null, storedSignCount);
    }

    ServerProperty serverProperty(byte[] expectedChallenge) {
        Set<Origin> origins = properties.getAllowedOrigins().stream().map(Origin::create).collect(Collectors.toSet());
        Challenge challenge = new DefaultChallenge(expectedChallenge);
        return ServerProperty.builder()
            .origins(origins)
            .rpId(properties.getRpId())
            .challenge(challenge)
            .build();
    }
}
