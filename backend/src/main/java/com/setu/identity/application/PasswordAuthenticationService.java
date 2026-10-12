package com.setu.identity.application;

import com.setu.identity.domain.User;
import com.setu.identity.infrastructure.UserRepository;
import com.setu.identity.infrastructure.security.Argon2PasswordService;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Argon2id password verification with account-level lockout and an audit
 * trail — the fallback/recovery path behind the primary passkey flow. Every
 * outcome (success, wrong password, locked, unknown email) is written to
 * governance.audit_logs via a direct call (never through an exception path),
 * so the audit row and any counter update always commit together — see
 * AuditLogWriter's javadoc for why.
 */
@Service
public class PasswordAuthenticationService {

    private static final String DUMMY_HASH_FOR_TIMING_PARITY =
        "$argon2id$v=19$m=65536,t=2,p=1$c29tZXNhbHRzb21lc2FsdA$ZGlnZXN0ZGlnZXN0ZGlnZXN0ZGlnZXN0ZGlnZXN0";

    private final UserRepository userRepository;
    private final Argon2PasswordService passwordService;
    private final AuditLogWriter auditLogWriter;

    public PasswordAuthenticationService(UserRepository userRepository, Argon2PasswordService passwordService,
                                          AuditLogWriter auditLogWriter) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.auditLogWriter = auditLogWriter;
    }

    @Transactional
    public PasswordAuthOutcome authenticate(String email, String rawPassword) {
        Instant now = Instant.now();
        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            // Still hash against a dummy value so a wrong-email response takes
            // roughly the same time as a wrong-password response — otherwise
            // response latency alone would let an attacker enumerate emails.
            passwordService.verify(rawPassword, DUMMY_HASH_FOR_TIMING_PARITY);
            auditLogWriter.write(null, null, "PASSWORD_VERIFY_FAILURE", "identity.users", null,
                Map.of("reason", "unknown_email"));
            return new PasswordAuthOutcome.InvalidCredentials();
        }
        User user = maybeUser.get();

        if (!user.isActive()) {
            // Same short-circuit as the lock check below (no dummy-hash timing parity needed — the account is
            // already known to exist, so there's no enumeration concern, only "don't verify a password that
            // wouldn't matter anyway"): a suspended/deactivated account must not authenticate, full stop.
            auditLogWriter.write(null, user.id(), "PASSWORD_VERIFY_FAILURE", "identity.users", user.id(),
                Map.of("reason", "account_" + user.status().name().toLowerCase()));
            return new PasswordAuthOutcome.AccountNotActive(user.status());
        }
        if (user.isPasswordLocked(now)) {
            auditLogWriter.write(null, user.id(), "PASSWORD_VERIFY_FAILURE", "identity.users", user.id(),
                Map.of("reason", "account_locked"));
            return new PasswordAuthOutcome.AccountLocked(now);
        }
        if (!user.hasPassword()) {
            passwordService.verify(rawPassword, DUMMY_HASH_FOR_TIMING_PARITY);
            auditLogWriter.write(null, user.id(), "PASSWORD_VERIFY_FAILURE", "identity.users", user.id(),
                Map.of("reason", "no_password_configured"));
            return new PasswordAuthOutcome.NoPasswordConfigured();
        }

        boolean matches = passwordService.verify(rawPassword, user.passwordHash());
        if (!matches) {
            user.recordFailedPasswordAttempt(now, passwordService.properties().getMaxFailedAttempts(),
                passwordService.properties().getLockoutMinutes());
            userRepository.save(user);
            auditLogWriter.write(null, user.id(), "PASSWORD_VERIFY_FAILURE", "identity.users", user.id(),
                Map.of("reason", "wrong_password", "failedAttempts", user.failedLoginAttempts()));
            return new PasswordAuthOutcome.InvalidCredentials();
        }

        user.recordSuccessfulLogin(now);
        userRepository.save(user);
        auditLogWriter.write(null, user.id(), "PASSWORD_VERIFY_SUCCESS", "identity.users", user.id(), null);
        return new PasswordAuthOutcome.Success(user.id());
    }
}
