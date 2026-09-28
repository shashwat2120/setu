package com.setu.attendance.infrastructure;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes the rotating QR token. Same shape as
 * {@code identity.infrastructure.security.RefreshTokenHasher} (high-entropy
 * random value, fast SHA-256 hash — no offline-guessing risk to slow down
 * with Argon2id, unlike a password) — duplicated here rather than imported,
 * since {@code identity.infrastructure} is that module's own internal
 * package and this is a small, generic, easily-duplicated utility, not a
 * business contract worth a cross-module port for.
 */
@Component
public class QrTokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    public String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String generateNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must always be available", e);
        }
    }
}
