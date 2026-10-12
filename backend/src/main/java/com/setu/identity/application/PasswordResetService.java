package com.setu.identity.application;

import com.setu.identity.application.email.EmailService;
import com.setu.identity.domain.PasswordPolicy;
import com.setu.identity.domain.PasswordResetToken;
import com.setu.identity.domain.User;
import com.setu.identity.infrastructure.PasswordResetTokenRepository;
import com.setu.identity.infrastructure.UserRepository;
import com.setu.identity.infrastructure.security.Argon2PasswordService;
import com.setu.identity.infrastructure.security.RefreshTokenHasher;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.identity.PersonDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Secure password reset/recovery. Only the token's hash is ever persisted
 * (see V51) — the raw token exists only in memory on this request and
 * whatever channel delivers it to the user. Actual delivery (email/SMS via
 * the notification module) is not wired up here — that's the notification
 * module's job and isn't part of Day 3's authentication foundation; the raw
 * token is handed back to the caller to deliver.
 * <p>
 * {@code @NamedInterface} added Day 15 §demo-data: {@code
 * shared.seed.DemoDataSeeder} sets every seeded account's initial password
 * through this SAME real {@code initiate}/{@code complete} token flow —
 * deliberately not a new "seed-only" password-setting bypass. Neither
 * method here has ever had an authorization check of its own (a password
 * reset is inherently pre-authentication), so this is already safe to call
 * from a bootstrap context with no signed-in principal.
 */
@org.springframework.modulith.NamedInterface
@Service
public class PasswordResetService {

    private static final long RESET_TOKEN_TTL_MINUTES = 30;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final RefreshTokenHasher tokenHasher; // SHA-256 opaque-token hashing is generic, reused here rather than duplicated
    private final Argon2PasswordService passwordService;
    private final AuditLogWriter auditLogWriter;
    private final SessionService sessionService;
    private final EmailService emailService;
    private final PersonDirectory personDirectory;
    private final String frontendBaseUrl;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository resetTokenRepository,
                                 RefreshTokenHasher tokenHasher, Argon2PasswordService passwordService,
                                 AuditLogWriter auditLogWriter, SessionService sessionService, EmailService emailService,
                                 PersonDirectory personDirectory,
                                 @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.tokenHasher = tokenHasher;
        this.passwordService = passwordService;
        this.auditLogWriter = auditLogWriter;
        this.sessionService = sessionService;
        this.emailService = emailService;
        this.personDirectory = personDirectory;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** Empty if the email doesn't exist — callers must respond identically either way (no user-enumeration via this endpoint). */
    @Transactional
    public Optional<String> initiate(String email) {
        Instant now = Instant.now();
        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            return Optional.empty();
        }
        User user = maybeUser.get();
        String rawToken = tokenHasher.generateRawToken();
        Instant expiresAt = now.plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES);
        resetTokenRepository.save(new PasswordResetToken(
            UUID.randomUUID(), user.id(), tokenHasher.hash(rawToken), expiresAt, now));
        auditLogWriter.write(null, user.id(), "PASSWORD_RESET_INITIATED", "identity.users", user.id(), null);
        return Optional.of(rawToken);
    }

    /**
     * The real forgot-password entry point (used by {@code PasswordAuthController}, never by the seeder — see
     * {@code DemoDataSeeder}'s own javadoc for why seeded accounts deliberately call the plain {@link #initiate}
     * below instead, avoiding real emails to fake {@code @demo.setu.local} addresses on every seed run).
     * Generates the token exactly as {@link #initiate} always has, then attempts delivery through {@link
     * EmailService} — a failed send is logged (by the {@code EmailService} implementation, never here) but never
     * changes this method's return value or throws, so the HTTP layer's response is identical whether the
     * account exists, whether the token was created, or whether the email actually went out; that uniformity is
     * what prevents user-enumeration and internal-error leakage, not a special case in the controller.
     * <p>
     * {@code @Transactional} here (not just on {@link #initiate}) is deliberate: {@link #initiate} is called via
     * plain self-invocation (bypassing this class's own transactional proxy), so without an ambient transaction
     * already active on this thread its {@code @Transactional} would be silently ignored. The one accepted
     * tradeoff, disclosed: the outbound Brevo call runs inside that same transaction, holding a pooled DB
     * connection for the duration of one external HTTP call — acceptable for a low-volume flow like password
     * reset, not something this method's design tries to optimize away.
     */
    @Transactional
    public Optional<String> initiateAndEmail(String email) {
        Optional<String> rawToken = initiate(email);
        rawToken.ifPresent(token -> {
            User user = userRepository.findByEmail(email).orElseThrow();
            String displayName = personDirectory.findByUserId(user.id())
                .map(PersonDirectory.PersonSummary::preferredName)
                .orElse(null);
            String resetUrl = frontendBaseUrl + "/reset-password?token=" + token;
            Instant expiresAt = Instant.now().plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES);
            emailService.sendPasswordResetEmail(email, displayName, resetUrl, expiresAt);
        });
        return rawToken;
    }

    /** {@code WEAK_PASSWORD}: the link is still valid and is NOT consumed, so the person can simply choose another password. */
    public enum CompletionResult {SUCCESS, INVALID_OR_EXPIRED_TOKEN, WEAK_PASSWORD}

    @Transactional
    public CompletionResult complete(String rawToken, String newRawPassword) {
        Instant now = Instant.now();
        Optional<PasswordResetToken> maybeToken = resetTokenRepository.findByTokenHash(tokenHasher.hash(rawToken));
        if (maybeToken.isEmpty() || !maybeToken.get().isUsable(now)) {
            return CompletionResult.INVALID_OR_EXPIRED_TOKEN;
        }
        PasswordResetToken token = maybeToken.get();
        User user = userRepository.findById(token.userId())
            .orElseThrow(() -> new IllegalStateException("password_reset_tokens.user_id references a missing user"));
        if (PasswordPolicy.violation(newRawPassword, user.email()).isPresent()) {
            return CompletionResult.WEAK_PASSWORD;
        }
        user.setPasswordHash(passwordService.hash(newRawPassword), now);
        user.recordSuccessfulLogin(now); // a successful reset also clears any existing lockout, same as a normal successful auth
        userRepository.save(user);
        token.markUsed(now);
        resetTokenRepository.save(token);
        // Any other link this user was sent must stop working too: otherwise an older, still-valid email could be used
        // to set the password again after the person has already chosen their new one.
        for (PasswordResetToken other : resetTokenRepository.findByUserIdAndUsedAtIsNull(user.id())) {
            other.markUsed(now);
            resetTokenRepository.save(other);
        }
        // The reset is the owner's recovery action, so whoever may be holding an old session (a lost phone, a stolen cookie)
        // must be signed out. The person signs in again with the new password.
        int sessionsRevoked = sessionService.revokeAllSessionsForUser(user.id(), "PASSWORD_RESET");
        auditLogWriter.write(null, user.id(), "PASSWORD_RESET_COMPLETED", "identity.users", user.id(),
            java.util.Map.of("sessionsRevoked", sessionsRevoked));
        return CompletionResult.SUCCESS;
    }
}
