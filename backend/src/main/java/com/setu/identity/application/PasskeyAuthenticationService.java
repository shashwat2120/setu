package com.setu.identity.application;

import com.setu.identity.domain.ChallengeType;
import com.setu.identity.domain.Passkey;
import com.setu.identity.domain.PasskeyRevokedEvent;
import com.setu.identity.domain.User;
import com.setu.identity.domain.WebAuthnChallenge;
import com.setu.identity.infrastructure.PasskeyRepository;
import com.setu.identity.infrastructure.UserRepository;
import com.setu.identity.infrastructure.WebAuthnChallengeRepository;
import com.setu.identity.infrastructure.security.PasskeyCeremonyVerifier;
import com.setu.identity.infrastructure.security.PasskeyVerificationException;
import com.setu.identity.infrastructure.security.WebAuthnProperties;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Orchestrates WebAuthn passkey authentication ceremonies; also passkey revocation/listing, since that's the same aggregate. */
@Service
@EnableConfigurationProperties(WebAuthnProperties.class)
public class PasskeyAuthenticationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasskeyRepository passkeyRepository;
    private final WebAuthnChallengeRepository challengeRepository;
    private final PasskeyCeremonyVerifier verifier;
    private final WebAuthnProperties properties;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;
    private final AuthorizationContext authorizationContext;

    public PasskeyAuthenticationService(UserRepository userRepository, PasskeyRepository passkeyRepository,
                                         WebAuthnChallengeRepository challengeRepository,
                                         PasskeyCeremonyVerifier verifier, WebAuthnProperties properties,
                                         AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter,
                                         AuthorizationContext authorizationContext) {
        this.userRepository = userRepository;
        this.passkeyRepository = passkeyRepository;
        this.challengeRepository = challengeRepository;
        this.verifier = verifier;
        this.properties = properties;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional
    public AuthenticationChallenge startAuthentication(Optional<String> email) {
        UUID userId = null;
        List<byte[]> allowCredentialIds = List.of();
        if (email.isPresent()) {
            Optional<User> user = userRepository.findByEmail(email.get());
            if (user.isPresent()) {
                userId = user.get().id();
                allowCredentialIds = passkeyRepository.findByUserId(userId).stream()
                    .filter(Passkey::isActive)
                    .map(Passkey::credentialId)
                    .toList();
            }
            // Unknown email: proceed as if usernameless (no allow-list) rather than
            // revealing whether the email exists via a different response shape.
        }

        return buildChallenge(userId, allowCredentialIds);
    }

    /**
     * Step-up variant (Day 4 instruction §8): the challenge is scoped to
     * ONE specific, already-authenticated user's own passkeys — never
     * usernameless — so completing it with a different account's passkey is
     * rejected by {@link #finishAuthentication}'s existing
     * challenge.userId() check, not a separate code path that could drift
     * out of sync.
     */
    @Transactional
    public AuthenticationChallenge startStepUpAuthentication(UUID userId) {
        List<byte[]> allowCredentialIds = passkeyRepository.findByUserId(userId).stream()
            .filter(Passkey::isActive)
            .map(Passkey::credentialId)
            .toList();
        return buildChallenge(userId, allowCredentialIds);
    }

    private AuthenticationChallenge buildChallenge(UUID userId, List<byte[]> allowCredentialIds) {
        Instant now = Instant.now();
        byte[] challenge = new byte[32];
        RANDOM.nextBytes(challenge);
        Instant expiresAt = now.plus(properties.getChallengeTtlSeconds(), ChronoUnit.SECONDS);
        WebAuthnChallenge entity = new WebAuthnChallenge(
            UUID.randomUUID(), userId, challenge, ChallengeType.AUTHENTICATION, properties.getRpId(), expiresAt, now);
        challengeRepository.save(entity);
        return new AuthenticationChallenge(entity.id(), challenge, properties.getRpId(), allowCredentialIds, 60_000L);
    }

    @Transactional
    public PasskeyAuthOutcome finishAuthentication(UUID challengeId, String authenticationResponseJson) {
        Instant now = Instant.now();
        Optional<WebAuthnChallenge> maybeChallenge = challengeRepository.findById(challengeId);
        if (maybeChallenge.isEmpty()) {
            return new PasskeyAuthOutcome.ChallengeExpiredOrUnknown();
        }
        WebAuthnChallenge challenge = maybeChallenge.get();
        if (challenge.type() != ChallengeType.AUTHENTICATION || !challenge.isUsable(now)) {
            return new PasskeyAuthOutcome.ChallengeExpiredOrUnknown();
        }

        byte[] claimedCredentialId = verifier.parseAuthentication(authenticationResponseJson).getCredentialId();
        Optional<Passkey> maybePasskey = passkeyRepository.findByCredentialId(claimedCredentialId);
        if (maybePasskey.isEmpty()) {
            auditLogWriter.write(null, challenge.userId(), "PASSKEY_AUTH_FAILURE", "identity.passkeys", null,
                java.util.Map.of("reason", "unknown_credential"));
            return new PasskeyAuthOutcome.UnknownCredential();
        }
        Passkey passkey = maybePasskey.get();
        if (!passkey.isActive()) {
            auditLogWriter.write(null, passkey.userId(), "PASSKEY_AUTH_FAILURE", "identity.passkeys", passkey.id(),
                java.util.Map.of("reason", "revoked_credential"));
            return new PasskeyAuthOutcome.RevokedCredential();
        }
        // A challenge issued for a specific user (username-first flow) must not be redeemable by a different user's credential.
        if (challenge.userId() != null && !challenge.userId().equals(passkey.userId())) {
            return new PasskeyAuthOutcome.UnknownCredential();
        }

        // Checked before the (expensive) cryptographic ceremony verification below, same as the revoked-credential
        // check just above it: a suspended/deactivated account must not authenticate, regardless of whether the
        // ceremony would otherwise have verified correctly.
        User user = userRepository.findById(passkey.userId()).orElseThrow(
            () -> new IllegalStateException("passkey " + passkey.id() + " references a nonexistent user"));
        if (!user.isActive()) {
            auditLogWriter.write(null, user.id(), "PASSKEY_AUTH_FAILURE", "identity.passkeys", passkey.id(),
                java.util.Map.of("reason", "account_" + user.status().name().toLowerCase()));
            return new PasskeyAuthOutcome.AccountNotActive(user.status());
        }

        PasskeyCeremonyVerifier.VerifiedAuthentication verified;
        try {
            verified = verifier.verifyAuthentication(authenticationResponseJson, challenge.challenge(),
                passkey.credentialId(), passkey.publicKey(), passkey.signCount());
        } catch (PasskeyVerificationException e) {
            auditLogWriter.write(null, passkey.userId(), "PASSKEY_AUTH_FAILURE", "identity.passkeys", passkey.id(),
                java.util.Map.of("reason", "ceremony_verification_failed"));
            return new PasskeyAuthOutcome.VerificationFailed(e.getMessage());
        }
        if (!passkey.isSignCountValid(verified.signCount())) {
            auditLogWriter.write(null, passkey.userId(), "PASSKEY_AUTH_FAILURE", "identity.passkeys", passkey.id(),
                java.util.Map.of("reason", "sign_count_regression"));
            return new PasskeyAuthOutcome.VerificationFailed("sign_count did not advance — possible cloned authenticator");
        }

        passkey.recordUse(verified.signCount(), now);
        passkeyRepository.save(passkey);
        challenge.consume(now);
        challengeRepository.save(challenge);
        auditLogWriter.write(null, passkey.userId(), "PASSKEY_AUTH_SUCCESS", "identity.passkeys", passkey.id(), null);
        return new PasskeyAuthOutcome.Success(passkey.userId(), passkey.id());
    }

    public enum RevocationResult {REVOKED, NOT_FOUND, NOT_OWNED_BY_USER}

    @Transactional
    public RevocationResult revoke(UUID userId, UUID passkeyId) {
        // Passkey management is step-up-gated (Day 4 instruction §8) — an
        // attacker who merely stole a still-valid access token (but never
        // reauthenticated with a passkey) cannot strip the account's real
        // credentials.
        authorizationContext.assertStepUp();
        Instant now = Instant.now();
        Optional<Passkey> maybePasskey = passkeyRepository.findById(passkeyId);
        if (maybePasskey.isEmpty()) {
            return RevocationResult.NOT_FOUND;
        }
        Passkey passkey = maybePasskey.get();
        if (!passkey.userId().equals(userId)) {
            return RevocationResult.NOT_OWNED_BY_USER;
        }
        passkey.revoke(now);
        passkeyRepository.save(passkey);
        var event = new PasskeyRevokedEvent(UUID.randomUUID(), now, passkey.id(), UUID.randomUUID(), null,
            passkey.id(), userId, userId);
        outboxEventWriter.write("passkeys", passkey.id(), event.eventType(), event.eventVersion(), event,
            event.tenantId(), event.correlationId(), event.causationId());
        auditLogWriter.write(null, userId, "PASSKEY_REVOKED", "identity.passkeys", passkey.id(),
            Map.of("revokedBy", userId));
        return RevocationResult.REVOKED;
    }

    @Transactional(readOnly = true)
    public List<Passkey> listForUser(UUID userId) {
        return passkeyRepository.findByUserId(userId);
    }
}
