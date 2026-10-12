package com.setu.identity.application;

import com.setu.identity.application.email.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 16 §email: {@link PasswordResetService#initiate}/{@link PasswordResetService#complete} token-lifecycle
 * coverage (unknown email, invalid/expired/reused token — none of this existed before this class), plus {@link
 * PasswordResetService#initiateAndEmail}'s wiring to {@link EmailService} and its provider-failure handling.
 */
@SpringBootTest
class PasswordResetServiceTests {

    @TestConfiguration
    static class RecordingEmailServiceConfig {
        @Bean
        @Primary
        RecordingEmailService recordingEmailService() {
            return new RecordingEmailService();
        }
    }

    static class RecordingEmailService implements EmailService {
        record Call(String toEmail, String displayName, String resetUrl, Instant expiresAt) {
        }

        final List<Call> calls = new ArrayList<>();
        final AtomicInteger failNext = new AtomicInteger(0);

        @Override
        public SendResult sendPasswordResetEmail(String toEmail, String displayName, String resetUrl, Instant expiresAt) {
            calls.add(new Call(toEmail, displayName, resetUrl, expiresAt));
            if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return SendResult.failure("simulated provider outage");
            }
            return SendResult.success("test-message-id");
        }
    }

    @Autowired
    PasswordResetService passwordResetService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    RecordingEmailService recordingEmailService;
    @Autowired
    SessionService sessionService;
    @Autowired
    com.setu.identity.infrastructure.SessionRepository sessionRepository;

    private UUID registeredUserId;

    private String registerUser() {
        String email = "reset-test-" + UUID.randomUUID() + "@example.com";
        var outcome = (UserRegistrationService.Outcome.Created) userRegistrationService.register(email);
        registeredUserId = outcome.userId();
        return email;
    }

    @Test
    void initiateReturnsEmptyForAnUnknownEmailNeverRevealingWhetherTheAccountExists() {
        Optional<String> token = passwordResetService.initiate("no-such-account-" + UUID.randomUUID() + "@example.com");
        assertThat(token).isEmpty();
    }

    @Test
    void fullResetCycleSucceeds() {
        String email = registerUser();
        String token = passwordResetService.initiate(email).orElseThrow();

        var result = passwordResetService.complete(token, "NewPassword#2025!");

        assertThat(result).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
    }

    @Test
    void aWeakPasswordIsRejectedAndTheLinkIsNotConsumedSoTheyCanTryAgain() {
        String email = registerUser();
        String token = passwordResetService.initiate(email).orElseThrow();

        assertThat(passwordResetService.complete(token, "short")).isEqualTo(PasswordResetService.CompletionResult.WEAK_PASSWORD);
        assertThat(passwordResetService.complete(token, "password123")).isEqualTo(PasswordResetService.CompletionResult.WEAK_PASSWORD);
        assertThat(passwordResetService.complete(token, email)).isEqualTo(PasswordResetService.CompletionResult.WEAK_PASSWORD);

        // the same link still works with an acceptable password
        assertThat(passwordResetService.complete(token, "AGoodNewPassword#7")).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
    }

    @Test
    void completingAResetSignsOutEveryExistingSessionOfThatUser() {
        String email = registerUser();
        UUID userId = registeredUserId;
        sessionService.startNewSession(userId, "{}");
        sessionService.startNewSession(userId, "{}");
        assertThat(sessionRepository.findByUserIdAndRevokedAtIsNull(userId)).hasSize(2);
        String token = passwordResetService.initiate(email).orElseThrow();

        assertThat(passwordResetService.complete(token, "AGoodNewPassword#7")).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);

        assertThat(sessionRepository.findByUserIdAndRevokedAtIsNull(userId)).isEmpty();
    }

    @Test
    void aRejectedWeakPasswordDoesNotSignAnyoneOut() {
        String email = registerUser();
        UUID userId = registeredUserId;
        sessionService.startNewSession(userId, "{}");
        String token = passwordResetService.initiate(email).orElseThrow();

        assertThat(passwordResetService.complete(token, "short")).isEqualTo(PasswordResetService.CompletionResult.WEAK_PASSWORD);

        assertThat(sessionRepository.findByUserIdAndRevokedAtIsNull(userId)).hasSize(1);
    }

    @Test
    void anOlderStillValidLinkStopsWorkingOnceANewerOneHasBeenUsed() {
        String email = registerUser();
        String older = passwordResetService.initiate(email).orElseThrow();
        String newer = passwordResetService.initiate(email).orElseThrow();

        assertThat(passwordResetService.complete(newer, "AGoodNewPassword#7")).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);

        assertThat(passwordResetService.complete(older, "AnotherGoodOne#8")).isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
    }

    @Test
    void completeRejectsAnInvalidToken() {
        var result = passwordResetService.complete("not-a-real-token-" + UUID.randomUUID(), "NewPassword#2025!");
        assertThat(result).isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
    }

    @Test
    void completeRejectsAnAlreadyUsedToken() {
        String email = registerUser();
        String token = passwordResetService.initiate(email).orElseThrow();
        assertThat(passwordResetService.complete(token, "FirstNewPassword#1!")).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);

        var reuseResult = passwordResetService.complete(token, "SecondNewPassword#2!");

        assertThat(reuseResult).isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
    }

    @Test
    void initiateAndEmailSendsToTheRealEmailServiceWithAWorkingResetUrlAndExpiry() {
        String email = registerUser();
        recordingEmailService.calls.clear();

        Optional<String> token = passwordResetService.initiateAndEmail(email);

        assertThat(token).isPresent();
        assertThat(recordingEmailService.calls).hasSize(1);
        RecordingEmailService.Call call = recordingEmailService.calls.get(0);
        assertThat(call.toEmail()).isEqualTo(email);
        assertThat(call.resetUrl()).contains("/reset-password?token=" + token.get());
        assertThat(call.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void initiateAndEmailStillReturnsTheTokenWhenTheEmailProviderFails() {
        String email = registerUser();
        recordingEmailService.failNext.set(1);

        Optional<String> token = passwordResetService.initiateAndEmail(email);

        assertThat(token).isPresent();
        var result = passwordResetService.complete(token.get(), "StillWorks#2025!");
        assertThat(result).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
    }

    @Test
    void initiateAndEmailSendsNothingForAnUnknownEmail() {
        recordingEmailService.calls.clear();
        Optional<String> token = passwordResetService.initiateAndEmail("no-such-account-" + UUID.randomUUID() + "@example.com");

        assertThat(token).isEmpty();
        assertThat(recordingEmailService.calls).isEmpty();
    }
}
