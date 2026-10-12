package com.setu.identity.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a new password must satisfy before it is hashed and stored. Enforced on the server: the reset page's own
 * {@code minLength} is only a browser hint, and anyone calling the API directly skips it.
 * <ul>
 *   <li>8 to {@value #MAX_LENGTH} characters. The upper bound exists because Argon2id cost grows with input, so an unbounded
 *       password would be a cheap way to burn server time.</li>
 *   <li>Not one of the most common passwords (a small built-in list; this is not a full breached-password database).</li>
 *   <li>Not the account's own email address or its local part.</li>
 * </ul>
 * Deliberately no "must contain a digit and a symbol" rule: length and not-being-guessable matter more than character classes.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    private static final Set<String> COMMON = Set.of(
        "password", "password1", "password12", "password123", "passw0rd", "p@ssw0rd", "p@ssword", "12345678", "123456789",
        "1234567890", "11111111", "00000000", "87654321", "qwertyui", "qwerty123", "qwertyuiop", "1q2w3e4r", "iloveyou",
        "admin123", "administrator", "welcome1", "welcome123", "letmein1", "letmein123", "changeme", "abc12345", "abcd1234",
        "monkey123", "dragon123", "football1", "baseball1", "sunshine1", "princess1", "trustno1", "setu1234", "setupassword");

    private PasswordPolicy() {
    }

    /** Empty when the password is acceptable; otherwise a short reason that is safe to show to the person choosing it. */
    public static Optional<String> violation(String password, String email) {
        if (password == null) {
            return Optional.of("a password is required");
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            return Optional.of("use at least " + MIN_LENGTH + " characters");
        }
        if (length > MAX_LENGTH) {
            return Optional.of("use at most " + MAX_LENGTH + " characters");
        }
        String lowered = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lowered)) {
            return Optional.of("that password is too common");
        }
        if (email != null && !email.isBlank()) {
            String loweredEmail = email.toLowerCase(Locale.ROOT);
            String local = loweredEmail.contains("@") ? loweredEmail.substring(0, loweredEmail.indexOf('@')) : loweredEmail;
            if (lowered.equals(loweredEmail) || (local.length() >= 4 && lowered.equals(local))) {
                return Optional.of("do not use your email address as your password");
            }
        }
        return Optional.empty();
    }
}
