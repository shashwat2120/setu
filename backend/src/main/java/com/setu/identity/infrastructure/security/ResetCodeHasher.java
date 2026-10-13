package com.setu.identity.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates and protects the six-digit password-reset code. A plain hash of a six-digit number is worthless (a million guesses
 * reverse it instantly), so the code is stored as an HMAC keyed with the server's signing secret, which is never in the database, and
 * bound to the user's id so one user's code can never match another's. A database read alone therefore cannot reveal a usable code.
 */
@Component
public class ResetCodeHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    public ResetCodeHasher(@Value("${app.security.jwt.secret}") String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** A uniformly random six-digit code, zero-padded ("004217"). */
    public String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    public String hash(UUID userId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((userId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 must always be available", e);
        }
    }

    /** Constant-time comparison, so response time does not reveal how many leading characters matched. */
    public boolean matches(UUID userId, String code, String storedHash) {
        return MessageDigest.isEqual(
            hash(userId, code).getBytes(StandardCharsets.UTF_8), storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
