package com.setu.identity.application.email;

import java.time.Instant;

/**
 * Password-reset email delivery, abstracted behind an interface so {@link
 * com.setu.identity.application.PasswordResetService} depends on a contract, never a specific provider
 * (matching this codebase's existing Argon2/JWT-style ports-and-adapters pattern). {@link
 * com.setu.identity.infrastructure.email.BrevoEmailService} is the only implementation today.
 * <p>
 * A failed send is never allowed to change what the caller (ultimately the HTTP client) sees — {@link
 * PasswordResetService#initiateAndEmail} intentionally ignores {@link SendResult#success()}: the response is
 * identical whether the account exists, whether the token was created, and whether delivery succeeded, which is
 * exactly what prevents this endpoint from being a user-enumeration or internal-error oracle.
 */
public interface EmailService {

    record SendResult(boolean success, String providerMessageId, String failureReason) {
        public static SendResult success(String providerMessageId) {
            return new SendResult(true, providerMessageId, null);
        }

        public static SendResult failure(String reason) {
            return new SendResult(false, null, reason);
        }
    }

    /**
     * Sends the six-digit reset {@code code}. {@code resetPageUrl} is the page where the code is entered: it carries no secret
     * (the code is the secret), so a link in a forwarded or logged email cannot be used on its own.
     */
    SendResult sendPasswordResetEmail(String toEmail, String displayName, String resetPageUrl, String code, Instant expiresAt);
}
