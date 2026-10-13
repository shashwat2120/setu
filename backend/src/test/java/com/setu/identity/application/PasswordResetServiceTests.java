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
        record Call(String toEmail, String displayName, String resetUrl, String code, Instant expiresAt) {
        }

        final List<Call> calls = new ArrayList<>();
        final AtomicInteger failNext = new AtomicInteger(0);

        @Override
        public SendResult sendPasswordResetEmail(String toEmail, String displayName, String resetUrl, String code, Instant expiresAt) {
            calls.add(new Call(toEmail, displayName, resetUrl, code, expiresAt));
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

    private static final String GOOD_PASSWORD = "AGoodNewPassword#7";

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String lastEmailedCode() {
        return recordingEmailService.calls.get(recordingEmailService.calls.size() - 1).code();
    }

    private static String wrongCodeFor(String right) {
        return right.equals("000000") ? "000001" : "000000";
    }

    @Test
    void initiateAndEmailSendsASixDigitCodeAndAPageAddressThatCarriesNoSecret() {
        String email = registerUser();
        recordingEmailService.calls.clear();

        Optional<String> code = passwordResetService.initiateAndEmail(email);

        assertThat(code).isPresent();
        assertThat(recordingEmailService.calls).hasSize(1);
        RecordingEmailService.Call call = recordingEmailService.calls.get(0);
        assertThat(call.toEmail()).isEqualTo(email);
        assertThat(call.code()).matches("\\d{6}").isEqualTo(code.get());
        assertThat(call.resetUrl()).endsWith("/reset-password").doesNotContain("?").doesNotContain(call.code());
        assertThat(call.expiresAt()).isAfter(Instant.now().plusSeconds(14 * 60)).isBefore(Instant.now().plusSeconds(16 * 60));
    }

    @Test
    void initiateAndEmailStillCreatesAWorkingCodeWhenTheEmailProviderFails() {
        String email = registerUser();
        recordingEmailService.failNext.set(1);

        Optional<String> code = passwordResetService.initiateAndEmail(email);

        assertThat(code).isPresent();
        assertThat(passwordResetService.completeWithCode(email, code.get(), GOOD_PASSWORD))
            .isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
    }

    @Test
    void initiateAndEmailSendsNothingForAnUnknownEmail() {
        recordingEmailService.calls.clear();
        Optional<String> code = passwordResetService.initiateAndEmail("no-such-account-" + UUID.randomUUID() + "@example.com");

        assertThat(code).isEmpty();
        assertThat(recordingEmailService.calls).isEmpty();
    }

    @Test
    void theEmailedCodeSetsANewPasswordAndWorksOnlyOnce() {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);
        String code = lastEmailedCode();

        assertThat(passwordResetService.completeWithCode(email, code, GOOD_PASSWORD)).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
        assertThat(passwordResetService.completeWithCode(email, code, "AnotherGoodOne#8"))
            .isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
    }

    @Test
    void wrongGuessesAreRejectedButTheRightCodeStillWorksWithinTheBudget() {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);
        String code = lastEmailedCode();

        for (int i = 0; i < PasswordResetService.MAX_CODE_ATTEMPTS - 1; i++) {
            assertThat(passwordResetService.completeWithCode(email, wrongCodeFor(code), GOOD_PASSWORD))
                .isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
        }

        assertThat(passwordResetService.completeWithCode(email, code, GOOD_PASSWORD)).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
    }

    @Test
    void afterTheGuessBudgetIsSpentEvenTheRightCodeIsRefused() {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);
        String code = lastEmailedCode();
        for (int i = 0; i < PasswordResetService.MAX_CODE_ATTEMPTS; i++) {
            passwordResetService.completeWithCode(email, wrongCodeFor(code), GOOD_PASSWORD);
        }

        assertThat(passwordResetService.completeWithCode(email, code, GOOD_PASSWORD))
            .as("a code that has absorbed its five guesses is dead, so brute force cannot win")
            .isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
    }

    @Test
    void aWeakPasswordCostsNoGuessAndDoesNotUseTheCodeUp() {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);
        String code = lastEmailedCode();

        for (int i = 0; i < PasswordResetService.MAX_CODE_ATTEMPTS + 2; i++) {
            assertThat(passwordResetService.completeWithCode(email, code, "short")).isEqualTo(PasswordResetService.CompletionResult.WEAK_PASSWORD);
        }

        assertThat(passwordResetService.completeWithCode(email, code, GOOD_PASSWORD)).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
    }

    @Test
    void anExpiredCodeIsRefused() {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);
        String code = lastEmailedCode();
        jdbc.update("update identity.password_reset_codes set expires_at = now() - interval '1 minute' where user_id = ?", registeredUserId);

        assertThat(passwordResetService.completeWithCode(email, code, GOOD_PASSWORD))
            .isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
    }

    @Test
    void requestingANewCodeReplacesTheOldOne() {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);
        String first = lastEmailedCode();
        passwordResetService.initiateAndEmail(email);
        String second = lastEmailedCode();
        org.junit.jupiter.api.Assumptions.assumeTrue(!first.equals(second), "astronomically unlikely collision of two random codes");

        assertThat(passwordResetService.completeWithCode(email, first, GOOD_PASSWORD))
            .isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
        assertThat(passwordResetService.completeWithCode(email, second, GOOD_PASSWORD)).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
    }

    @Test
    void anUnknownEmailAndMalformedCodesAreIndistinguishableFromAWrongCode() {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);

        assertThat(passwordResetService.completeWithCode("nobody-" + UUID.randomUUID() + "@example.com", "123456", GOOD_PASSWORD))
            .isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
        for (String malformed : java.util.Arrays.asList(null, "", "abc123", "12345", "1234567", "12 34 56")) {
            assertThat(passwordResetService.completeWithCode(email, malformed, GOOD_PASSWORD)).as("code: %s", malformed)
                .isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
        }
    }

    @Test
    void aCodeIsBoundToItsOwnerAndCannotResetSomeoneElse() {
        String ownerEmail = registerUser();
        passwordResetService.initiateAndEmail(ownerEmail);
        String ownersCode = lastEmailedCode();
        String otherEmail = registerUser();
        passwordResetService.initiateAndEmail(otherEmail);

        assertThat(passwordResetService.completeWithCode(otherEmail, ownersCode, GOOD_PASSWORD))
            .isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
    }

    @Test
    void aSuccessfulCodeResetSignsOutEverySessionAndCancelsOtherCodes() {
        String email = registerUser();
        UUID userId = registeredUserId;
        sessionService.startNewSession(userId, "{}");
        sessionService.startNewSession(userId, "{}");
        passwordResetService.initiateAndEmail(email);
        String code = lastEmailedCode();

        assertThat(passwordResetService.completeWithCode(email, code, GOOD_PASSWORD)).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);

        assertThat(sessionRepository.findByUserIdAndRevokedAtIsNull(userId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from identity.password_reset_codes where user_id = ? and used_at is null", Integer.class, userId))
            .isZero();
    }

    @Test
    void theCodeIsNeverStoredInPlainText() {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);
        String code = lastEmailedCode();

        String stored = jdbc.queryForObject("select code_hash from identity.password_reset_codes where user_id = ?", String.class, registeredUserId);

        assertThat(stored).isNotEqualTo(code).doesNotContain(code).matches("[0-9a-f]{64}");
    }

    @Test
    void parallelGuessesCanNeverSpendMoreThanTheBudget() throws Exception {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);
        String code = lastEmailedCode();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(12);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<PasswordResetService.CompletionResult>>();
            for (int i = 0; i < 24; i++) {
                futures.add(pool.submit(() -> passwordResetService.completeWithCode(email, wrongCodeFor(code), GOOD_PASSWORD)));
            }
            for (var f : futures) {
                assertThat(f.get()).isEqualTo(PasswordResetService.CompletionResult.INVALID_OR_EXPIRED_TOKEN);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer attempts = jdbc.queryForObject("select attempts from identity.password_reset_codes where user_id = ?", Integer.class, registeredUserId);
        assertThat(attempts).as("24 parallel guesses, but only five were ever accepted").isEqualTo(PasswordResetService.MAX_CODE_ATTEMPTS);
    }
}
