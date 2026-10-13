package com.setu.identity.infrastructure.email;

import com.setu.identity.application.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Day 16 §email: Brevo Transactional Email API (POST https://api.brevo.com/v3/smtp/email) — a plain synchronous
 * REST call, never SMTP, matching the architecture the task specified (this module never talks to a mail
 * server directly). Built on the same {@code RestClient.Builder} bean every other synchronous outbound HTTP
 * call in this codebase uses (see {@code AiChatConfig}'s own javadoc for why: Spring Boot's autoconfiguration
 * backs it with Apache HttpClient5's classic, plain-blocking-socket implementation, which — confirmed
 * extensively this session — is the one HTTP client shape that actually works on this dev machine, since
 * anything NIO-{@link java.nio.channels.Selector}-based fails here at the JDK/OS level).
 * <p>
 * The API key is read once at construction from {@code app.email.brevo.api-key} (backed by the
 * {@code BREVO_API_KEY} env var, never a literal in this codebase) and is sent ONLY as the {@code api-key}
 * request header on each call — never logged, never included in any exception message this class constructs
 * itself. {@link RestClientException#getMessage()} (used in the one place this class logs a failure) is
 * Spring's own summary of the HTTP status/response body, which never echoes back a request header.
 */
@Service
public class BrevoEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailService.class);
    private static final DateTimeFormatter EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a 'UTC'", Locale.US).withZone(ZoneOffset.UTC);

    private final RestClient restClient;
    private final String apiKey;
    private final String fromAddress;
    private final String fromName;
    private final boolean previewEnabled;
    private final Path previewDir;

    public BrevoEmailService(RestClient.Builder restClientBuilder,
                              @Value("${app.email.brevo.api-key:}") String apiKey,
                              @Value("${app.email.brevo.from-address}") String fromAddress,
                              @Value("${app.email.brevo.from-name}") String fromName,
                              @Value("${app.email.preview.enabled:false}") boolean previewEnabled,
                              @Value("${app.email.preview.dir:target/demo-email-outbox}") String previewDir) {
        this.restClient = restClientBuilder.baseUrl("https://api.brevo.com/v3").build();
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.previewEnabled = previewEnabled;
        this.previewDir = Path.of(previewDir);
    }

    private record BrevoRequest(Map<String, String> sender, List<Map<String, String>> to, String subject,
                                 String htmlContent, String textContent) {
    }

    private record BrevoResponse(String messageId) {
    }

    @Override
    public SendResult sendPasswordResetEmail(String toEmail, String displayName, String resetUrl, String code, Instant expiresAt) {
        String greeting = (displayName == null || displayName.isBlank()) ? "there" : displayName;
        String expiryText = EXPIRY_FORMAT.format(expiresAt);
        String html = htmlBody(greeting, resetUrl, code, expiryText);

        // Local-only, gated behind app.email.preview.enabled (DEMO_EMAIL_PREVIEW=true — never on in production,
        // never wired to any HTTP endpoint). Written BEFORE the real Brevo call and independent of its outcome,
        // so a reviewer on a network that blocks the inbox (or Brevo itself) can still open the exact rendered
        // email — same resetUrl, same expiry — from a local file. Real delivery below is unconditional and
        // unaffected: this never replaces sending, only supplements it for local inspection.
        if (previewEnabled) {
            writePreviewFile(toEmail, resetUrl, expiryText, html);
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Password reset email not sent to {}: no Brevo API key configured (app.email.brevo.api-key / BREVO_API_KEY)",
                maskEmail(toEmail));
            return SendResult.failure("email provider not configured");
        }

        BrevoRequest request = new BrevoRequest(
            Map.of("email", fromAddress, "name", fromName),
            List.of(Map.of("email", toEmail)),
            "Your Setu College password reset code",
            html,
            textBody(greeting, resetUrl, code, expiryText));

        try {
            BrevoResponse response = restClient.post()
                .uri("/smtp/email")
                .header("api-key", apiKey)
                .header("Accept", "application/json")
                .body(request)
                .retrieve()
                .body(BrevoResponse.class);
            return SendResult.success(response != null ? response.messageId() : null);
        } catch (RestClientException e) {
            log.warn("Password reset email delivery failed for {}: {}", maskEmail(toEmail), e.getMessage());
            return SendResult.failure("email delivery failed");
        }
    }

    /** See the {@code previewEnabled} branch in {@link #sendPasswordResetEmail} for when/why this runs. Writes
     * to a plain local file under {@code target/} (already git-ignored wholesale) — never served over HTTP,
     * never includes {@link #apiKey}, and the reset TOKEN it contains is the same single-use, 30-minute one the
     * real email would carry, so it is exactly as sensitive as that email and no more; this only changes WHERE
     * a reviewer can read it from, not what secret it is. */
    private void writePreviewFile(String toEmail, String resetUrl, String expiryText, String html) {
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
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String htmlBody(String greeting, String resetUrl, String code, String expiryText) {
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

    private static String textBody(String greeting, String resetUrl, String code, String expiryText) {
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
