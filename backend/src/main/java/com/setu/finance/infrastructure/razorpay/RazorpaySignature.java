package com.setu.finance.infrastructure.razorpay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Razorpay signs a webhook with HMAC-SHA256, keyed with the webhook secret, over the EXACT bytes of the request body, and sends it as lowercase hex in
 * {@code X-Razorpay-Signature}. So the check must run on the raw body before anything parses it: re-serialising parsed JSON would not reproduce the
 * bytes. The comparison is constant time, so how many leading characters matched cannot be learned from how long the answer took.
 */
public final class RazorpaySignature {

    private RazorpaySignature() {
    }

    public static String sign(byte[] rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** False for a missing or blank secret or signature: with no secret configured nothing can be verified, so nothing is accepted. */
    public static boolean verify(byte[] rawBody, String signature, String secret) {
        if (rawBody == null || secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = sign(rawBody, secret).getBytes(StandardCharsets.UTF_8);
        byte[] given = signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given);
    }
}
