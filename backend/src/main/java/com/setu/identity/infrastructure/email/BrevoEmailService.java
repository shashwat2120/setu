package com.setu.identity.infrastructure.email;

import com.setu.identity.application.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.setu.identity.infrastructure.email.PasswordResetEmailContent.maskEmail;

/**
 * Day 16 §email: Brevo Transactional Email API (POST https://api.brevo.com/v3/smtp/email) — a plain synchronous
 * REST call. Built on the same {@code RestClient.Builder} bean every other synchronous outbound HTTP
 * call in this codebase uses (see {@code AiChatConfig}'s own javadoc for why: Spring Boot's autoconfiguration
 * backs it with Apache HttpClient5's classic, plain-blocking-socket implementation, which — confirmed
 * extensively this session — is the one HTTP client shape that actually works on this dev machine, since
 * anything NIO-{@link java.nio.channels.Selector}-based fails here at the JDK/OS level).
 * <p>
 * Active when {@code app.email.provider=brevo} (the default); {@link SmtpEmailService} is the alternative.
 * <p>
 * The API key is read once at construction from {@code app.email.brevo.api-key} (backed by the
 * {@code BREVO_API_KEY} env var, never a literal in this codebase) and is sent ONLY as the {@code api-key}
 * request header on each call — never logged, never included in any exception message this class constructs
 * itself. {@link RestClientException#getMessage()} (used in the one place this class logs a failure) is
 * Spring's own summary of the HTTP status/response body, which never echoes back a request header.
 */
@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "brevo", matchIfMissing = true)
public class BrevoEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailService.class);

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
        String greeting = PasswordResetEmailContent.greeting(displayName);
        String expiryText = PasswordResetEmailContent.expiryText(expiresAt);
        String html = PasswordResetEmailContent.htmlBody(greeting, resetUrl, code, expiryText);

        // Local-only, gated behind app.email.preview.enabled (never on in production, never wired to any HTTP endpoint).
        // Written BEFORE the real Brevo call and independent of its outcome, so a reviewer on a network that blocks the
        // inbox (or Brevo itself) can still open the exact rendered email from a local file. Real delivery below is
        // unconditional and unaffected: this never replaces sending, only supplements it for local inspection.
        if (previewEnabled) {
            PasswordResetEmailContent.writePreview(log, previewDir, toEmail, resetUrl, expiryText, html);
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Password reset email not sent to {}: no Brevo API key configured (app.email.brevo.api-key / BREVO_API_KEY)",
                maskEmail(toEmail));
            return SendResult.failure("email provider not configured");
        }

        BrevoRequest request = new BrevoRequest(
            Map.of("email", fromAddress, "name", fromName),
            List.of(Map.of("email", toEmail)),
            PasswordResetEmailContent.SUBJECT,
            html,
            PasswordResetEmailContent.textBody(greeting, resetUrl, code, expiryText));

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
}
