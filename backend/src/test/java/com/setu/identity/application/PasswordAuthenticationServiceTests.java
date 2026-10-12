package com.setu.identity.application;

import com.setu.identity.infrastructure.UserRepository;
import com.setu.identity.infrastructure.security.Argon2PasswordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real Postgres (see infra/docker) with a real Argon2PasswordService
 * bean — same pattern as MigrationIntegrationTests. @Transactional rolls
 * back each test so users created here never leak between test runs.
 */
@SpringBootTest
@Transactional
class PasswordAuthenticationServiceTests {

    @Autowired
    UserRegistrationService registrationService;
    @Autowired
    PasswordAuthenticationService passwordAuthenticationService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    Argon2PasswordService passwordService;

    private UUID registerUserWithPassword(String email, String password) {
        var outcome = (UserRegistrationService.Outcome.Created) registrationService.register(email);
        var user = userRepository.findById(outcome.userId()).orElseThrow();
        user.setPasswordHash(passwordService.hash(password), Instant.now());
        userRepository.save(user);
        return outcome.userId();
    }

    @Test
    void correctPasswordAuthenticatesSuccessfully() {
        String email = "correct-" + UUID.randomUUID() + "@example.com";
        UUID userId = registerUserWithPassword(email, "correct-password-123");

        var outcome = passwordAuthenticationService.authenticate(email, "correct-password-123");

        assertThat(outcome).isInstanceOf(PasswordAuthOutcome.Success.class);
        assertThat(((PasswordAuthOutcome.Success) outcome).userId()).isEqualTo(userId);
    }

    @Test
    void wrongPasswordIsRejected() {
        String email = "wrong-" + UUID.randomUUID() + "@example.com";
        registerUserWithPassword(email, "correct-password-123");

        var outcome = passwordAuthenticationService.authenticate(email, "totally-wrong-password");

        assertThat(outcome).isInstanceOf(PasswordAuthOutcome.InvalidCredentials.class);
    }

    @Test
    void unknownEmailIsRejectedTheSameWayAsWrongPassword_noUserEnumeration() {
        var outcome = passwordAuthenticationService.authenticate(
            "nobody-" + UUID.randomUUID() + "@example.com", "whatever");

        assertThat(outcome).isInstanceOf(PasswordAuthOutcome.InvalidCredentials.class);
    }

    @Test
    void accountLocksAfterMaxFailedAttempts_bruteForceProtection() {
        String email = "lockout-" + UUID.randomUUID() + "@example.com";
        registerUserWithPassword(email, "correct-password-123");
        int maxAttempts = passwordService.properties().getMaxFailedAttempts();

        for (int i = 0; i < maxAttempts; i++) {
            var outcome = passwordAuthenticationService.authenticate(email, "wrong-" + i);
            assertThat(outcome).isInstanceOf(PasswordAuthOutcome.InvalidCredentials.class);
        }

        // The account is now locked — even the CORRECT password must be rejected.
        var outcomeAfterLockout = passwordAuthenticationService.authenticate(email, "correct-password-123");
        assertThat(outcomeAfterLockout).isInstanceOf(PasswordAuthOutcome.AccountLocked.class);
    }

    @Test
    void suspendedAccountCannotAuthenticateEvenWithTheCorrectPassword() {
        String email = "suspended-login-" + UUID.randomUUID() + "@example.com";
        UUID userId = registerUserWithPassword(email, "correct-password-123");
        var user = userRepository.findById(userId).orElseThrow();
        user.suspend();
        userRepository.save(user);

        var outcome = passwordAuthenticationService.authenticate(email, "correct-password-123");

        assertThat(outcome).isInstanceOf(PasswordAuthOutcome.AccountNotActive.class);
        assertThat(((PasswordAuthOutcome.AccountNotActive) outcome).status())
            .isEqualTo(com.setu.identity.domain.UserStatus.SUSPENDED);
    }

    @Test
    void deactivatedAccountCannotAuthenticateEvenWithTheCorrectPassword() {
        String email = "deactivated-login-" + UUID.randomUUID() + "@example.com";
        UUID userId = registerUserWithPassword(email, "correct-password-123");
        var user = userRepository.findById(userId).orElseThrow();
        user.deactivate();
        userRepository.save(user);

        var outcome = passwordAuthenticationService.authenticate(email, "correct-password-123");

        assertThat(outcome).isInstanceOf(PasswordAuthOutcome.AccountNotActive.class);
        assertThat(((PasswordAuthOutcome.AccountNotActive) outcome).status())
            .isEqualTo(com.setu.identity.domain.UserStatus.DEACTIVATED);
    }
}
