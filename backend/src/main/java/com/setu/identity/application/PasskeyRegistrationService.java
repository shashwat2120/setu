package com.setu.identity.application;

import com.setu.identity.domain.ChallengeType;
import com.setu.identity.domain.Passkey;
import com.setu.identity.domain.User;
import com.setu.identity.domain.WebAuthnChallenge;
import com.setu.identity.infrastructure.PasskeyRepository;
import com.setu.identity.infrastructure.UserRepository;
import com.setu.identity.infrastructure.WebAuthnChallengeRepository;
import com.setu.identity.infrastructure.security.PasskeyCeremonyVerifier;
import com.setu.identity.infrastructure.security.PasskeyVerificationException;
import com.setu.identity.infrastructure.security.WebAuthnProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/** Orchestrates WebAuthn passkey registration: challenge issuance/expiry/single-use around PasskeyCeremonyVerifier's pure ceremony verification. */
@Service
@EnableConfigurationProperties(WebAuthnProperties.class)
public class PasskeyRegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasskeyRepository passkeyRepository;
    private final WebAuthnChallengeRepository challengeRepository;
    private final PasskeyCeremonyVerifier verifier;
    private final WebAuthnProperties properties;

    public PasskeyRegistrationService(UserRepository userRepository, PasskeyRepository passkeyRepository,
                                       WebAuthnChallengeRepository challengeRepository,
                                       PasskeyCeremonyVerifier verifier, WebAuthnProperties properties) {
        this.userRepository = userRepository;
        this.passkeyRepository = passkeyRepository;
        this.challengeRepository = challengeRepository;
        this.verifier = verifier;
        this.properties = properties;
    }

    /**
     * {@code authenticatedUserId} is the caller's own identity, if any (from
     * the bearer token — empty for the unauthenticated bootstrap case right
     * after {@code /auth/register}). Attaching a passkey to an account that
     * already has a credential requires the caller to already be
     * authenticated as that exact account; a brand-new, credential-less
     * account may bootstrap its first passkey without one — see
     * {@link #isAuthorized}.
     */
    @Transactional
    public StartRegistrationResult startRegistration(UUID userId, Optional<UUID> authenticatedUserId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + userId));
        if (!isAuthorized(userId, authenticatedUserId)) {
            return new StartRegistrationResult.NotAuthorized();
        }
        Instant now = Instant.now();
        byte[] challenge = new byte[32];
        RANDOM.nextBytes(challenge);
        Instant expiresAt = now.plus(properties.getChallengeTtlSeconds(), ChronoUnit.SECONDS);
        WebAuthnChallenge entity = new WebAuthnChallenge(
            UUID.randomUUID(), userId, challenge, ChallengeType.REGISTRATION, properties.getRpId(), expiresAt, now);
        challengeRepository.save(entity);
        return new StartRegistrationResult.Started(new RegistrationChallenge(entity.id(), challenge,
            userHandleFor(userId), properties.getRpId(), properties.getRpName(), user.email(), 60_000L));
    }

    public sealed interface StartRegistrationResult {
        record Started(RegistrationChallenge challenge) implements StartRegistrationResult {
        }

        record NotAuthorized() implements StartRegistrationResult {
        }
    }

    private boolean isAuthorized(UUID targetUserId, Optional<UUID> authenticatedUserId) {
        if (authenticatedUserId.isPresent()) {
            return authenticatedUserId.get().equals(targetUserId);
        }
        boolean hasActivePasskey = passkeyRepository.findByUserId(targetUserId).stream().anyMatch(Passkey::isActive);
        boolean hasPassword = userRepository.findById(targetUserId).map(User::hasPassword).orElse(false);
        return !hasActivePasskey && !hasPassword;
    }

    @Transactional
    public RegistrationOutcome finishRegistration(UUID userId, Optional<UUID> authenticatedUserId, UUID challengeId,
                                                   String registrationResponseJson) {
        if (!isAuthorized(userId, authenticatedUserId)) {
            return new RegistrationOutcome.NotAuthorized();
        }
        Instant now = Instant.now();
        Optional<WebAuthnChallenge> maybeChallenge = challengeRepository.findById(challengeId);
        if (maybeChallenge.isEmpty()) {
            return new RegistrationOutcome.ChallengeExpiredOrUnknown();
        }
        WebAuthnChallenge challenge = maybeChallenge.get();
        boolean belongsToUser = challenge.userId() != null && challenge.userId().equals(userId);
        if (challenge.type() != ChallengeType.REGISTRATION || !belongsToUser || !challenge.isUsable(now)) {
            return new RegistrationOutcome.ChallengeExpiredOrUnknown();
        }

        PasskeyCeremonyVerifier.VerifiedRegistration verified;
        try {
            verified = verifier.verifyRegistration(registrationResponseJson, challenge.challenge());
        } catch (PasskeyVerificationException e) {
            return new RegistrationOutcome.VerificationFailed(e.getMessage());
        }

        if (passkeyRepository.findByCredentialId(verified.credentialId()).isPresent()) {
            return new RegistrationOutcome.CredentialAlreadyRegistered();
        }

        Passkey passkey = new Passkey(UUID.randomUUID(), userId, verified.credentialId(), verified.publicKeyCose(),
            verified.signCount(), verified.aaguid(), verified.transports().toArray(new String[0]),
            verified.backedUp(), now);
        passkeyRepository.save(passkey);
        challenge.consume(now);
        challengeRepository.save(challenge);
        return new RegistrationOutcome.Success(passkey.id());
    }

    /** Raw bytes of the user's own UUID — opaque, non-PII, stable per user. Never the email/username. */
    static byte[] userHandleFor(UUID userId) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(userId.getMostSignificantBits());
        buffer.putLong(userId.getLeastSignificantBits());
        return buffer.array();
    }
}
