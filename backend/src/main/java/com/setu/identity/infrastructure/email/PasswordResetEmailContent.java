package com.setu.identity.infrastructure.email;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What a password-reset email says and how it is previewed locally, shared by every sender so the wording, the
 * expiry format and the preview file are identical whichever provider is configured. Nothing here talks to a provider.
 */
final class PasswordResetEmailContent {

    static final String SUBJECT = "Your Setu College password reset code";

    private static final DateTimeFormatter EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a 'UTC'", Locale.US).withZone(ZoneOffset.UTC);

    private PasswordResetEmailContent() {
    }

    static String greeting(String displayName) {
        return (displayName == null || displayName.isBlank()) ? "there" : displayName;
    }

    static String expiryText(Instant expiresAt) {
        return EXPIRY_FORMAT.format(expiresAt);
    }

    /**
     * Local-only: writes the exact rendered email to a plain file (under {@code target/}, git-ignored, never served over HTTP) so a
     * reviewer whose inbox is unreachable can still read the code. Called by a sender before the real send and independent of its
     * outcome; it supplements sending and never replaces it. The file holds the same single-use code the real email carries, so it is
     * exactly as sensitive as that email and no more.
     */
    static void writePreview(Logger log, Path previewDir, String toEmail, String resetUrl, String expiryText, String html) {
        try {
            Files.createDirectories(previewDir);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
            String safeLocalPart = toEmail.replaceAll("[^A-Za-z0-9.+_-]", "_");
            Path file = previewDir.resolve(stamp + "-" + safeLocalPart + ".html");
            Files.writeString(file, html, StandardCharsets.UTF_8);
            log.info("DEMO email preview written for {} -> {} (reset code expires {}): {}",
                maskEmail(toEmail), file.toAbsolutePath(), expiryText, resetUrl);
        } catch (IOException e) {
            log.warn("Could not write demo email preview for {}: {}", maskEmail(toEmail), e.getMessage());
        }
    }

    /** Never log a full email address in a way that could end up correlated with the reset itself in shared logs. */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    static String htmlBody(String greeting, String resetUrl, String code, String expiryText) {
        return """
            <!DOCTYPE html>
            <html>
              <body style="font-family: -apple-system, Segoe UI, Roboto, sans-serif; background: #eae7dd; padding: 32px; color: #1b2436;">
                <div style="max-width: 480px; margin: 0 auto; background: #f7f5ee; border: 1px solid #c7c2b0; border-radius: 8px; padding: 32px;">
                  <p style="font-size: 20px; font-weight: 700; margin: 0 0 24px;">Setu College</p>
                  <p style="margin: 0 0 16px;">Hi %s,</p>
                  <p style="margin: 0 0 16px;">
                    We received a request to reset the password for your Setu College account. Use this code to choose a new password:
                  </p>
                  <p style="margin: 24px 0; font-family: Consolas, Menlo, monospace; font-size: 34px; font-weight: 700;
                     letter-spacing: 8px; color: #8a5d18;">%s</p>
                  <p style="margin: 0 0 16px;">
                    Enter it, with your email address, on the reset page:<br>
                    <a href="%s" style="color: #8a5d18;">%s</a>
                  </p>
                  <p style="margin: 0 0 16px; font-size: 13px; color: #52596b;">
                    This code expires on %s and works once. Never share it with anyone: Setu staff will never ask you for it.
                  </p>
                  <p style="margin: 24px 0 0; font-size: 13px; color: #52596b;">
                    If you did not request this, you can safely ignore this email. Your password stays as it is.
                  </p>
                </div>
              </body>
            </html>
            """.formatted(escapeHtml(greeting), code, resetUrl, resetUrl, expiryText);
    }

    static String textBody(String greeting, String resetUrl, String code, String expiryText) {
        return """
            Hi %s,

            We received a request to reset the password for your Setu College account.

            Your reset code: %s

            Enter it, with your email address, here: %s

            This code expires on %s and works once. Never share it with anyone: Setu staff will never ask you for it.

            If you did not request this, you can safely ignore this email. Your password stays as it is.
            """.formatted(greeting, code, resetUrl, expiryText);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
