package com.setu.identity.infrastructure.email;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.setu.identity.application.email.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Day 16 §email: real request construction against Brevo's documented contract (POST
 * https://api.brevo.com/v3/smtp/email, {@code api-key} header, sender/to/subject/htmlContent/textContent body) —
 * {@link MockRestServiceServer} intercepts the actual HTTP call this class makes, so this proves the request
 * shape without a real network call or a real API key.
 */
class BrevoEmailServiceTests {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(BrevoEmailService.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BrevoEmailService.class)).detachAppender(logAppender);
    }

    private BrevoEmailService service(String apiKey) {
        return new BrevoEmailService(builder, apiKey, "no-reply@setu.local", "Setu College", false, "target/unused-preview-dir");
    }

    private BrevoEmailService previewingService(String apiKey, Path previewDir) {
        return new BrevoEmailService(builder, apiKey, "no-reply@setu.local", "Setu College", true, previewDir.toString());
    }

    @Test
    void sendsARealRequestMatchingBrevosDocumentedContract() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("api-key", "test-key-123"))
            .andExpect(jsonPath("$.sender.email").value("no-reply@setu.local"))
            .andExpect(jsonPath("$.sender.name").value("Setu College"))
            .andExpect(jsonPath("$.to[0].email").value("student@example.com"))
            .andExpect(jsonPath("$.subject").value("Your Setu College password reset code"))
            .andExpect(jsonPath("$.htmlContent").exists())
            .andExpect(jsonPath("$.textContent").exists())
            .andRespond(withSuccess("{\"messageId\":\"<abc123@smtp-relay.brevo.com>\"}", MediaType.APPLICATION_JSON));

        EmailService.SendResult result = service("test-key-123").sendPasswordResetEmail(
            "student@example.com", "Ishaan", "http://localhost:5180/reset-password", "482916",
            Instant.now().plus(30, ChronoUnit.MINUTES));

        server.verify();
        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("<abc123@smtp-relay.brevo.com>");
    }

    @Test
    void emailContentIncludesGreetingTheCodeThePageAddressAndExpiry() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andExpect(jsonPath("$.htmlContent", org.hamcrest.Matchers.containsString("Ishaan")))
            .andExpect(jsonPath("$.htmlContent", org.hamcrest.Matchers.containsString("482916")))
            .andExpect(jsonPath("$.htmlContent", org.hamcrest.Matchers.containsString("http://localhost:5180/reset-password")))
            .andExpect(jsonPath("$.htmlContent", org.hamcrest.Matchers.containsString("works once")))
            .andExpect(jsonPath("$.htmlContent", org.hamcrest.Matchers.containsString("Never share it")))
            .andExpect(jsonPath("$.htmlContent", org.hamcrest.Matchers.containsString(
                "If you did not request this, you can safely ignore this email.")))
            .andExpect(jsonPath("$.textContent", org.hamcrest.Matchers.containsString("Your reset code: 482916")))
            .andExpect(jsonPath("$.textContent", org.hamcrest.Matchers.containsString("http://localhost:5180/reset-password")))
            .andRespond(withSuccess("{\"messageId\":\"x\"}", MediaType.APPLICATION_JSON));

        service("test-key-123").sendPasswordResetEmail(
            "student@example.com", "Ishaan", "http://localhost:5180/reset-password", "482916",
            Instant.now().plus(30, ChronoUnit.MINUTES));

        server.verify();
    }

    @Test
    void fallsBackToAGenericGreetingWhenNoDisplayNameIsAvailable() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andExpect(jsonPath("$.htmlContent", org.hamcrest.Matchers.containsString("Hi there,")))
            .andRespond(withSuccess("{\"messageId\":\"x\"}", MediaType.APPLICATION_JSON));

        service("test-key-123").sendPasswordResetEmail(
            "student@example.com", null, "http://localhost:5180/reset-password", "482916",
            Instant.now().plus(30, ChronoUnit.MINUTES));

        server.verify();
    }

    @Test
    void reportsFailureWithoutThrowingWhenNoApiKeyIsConfigured() {
        EmailService.SendResult result = service("").sendPasswordResetEmail(
            "student@example.com", "Ishaan", "http://localhost:5180/reset-password", "482916", Instant.now());

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    void reportsFailureWithoutThrowingWhenBrevoReturnsAnError() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        EmailService.SendResult result = service("bad-key").sendPasswordResetEmail(
            "student@example.com", "Ishaan", "http://localhost:5180/reset-password", "482916", Instant.now());

        server.verify();
        assertThat(result.success()).isFalse();
    }

    @Test
    void neverLogsTheApiKeyOnSuccessOrFailure() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        service("super-secret-api-key-value").sendPasswordResetEmail(
            "student@example.com", "Ishaan", "http://localhost:5180/reset-password", "482916", Instant.now());

        server.verify();
        boolean leaked = logAppender.list.stream()
            .anyMatch(event -> event.getFormattedMessage().contains("super-secret-api-key-value"));
        assertThat(leaked).isFalse();
    }

    @Test
    void writesNoLocalPreviewFileWhenPreviewIsDisabled(@TempDir Path tempDir) throws IOException {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andRespond(withSuccess("{\"messageId\":\"x\"}", MediaType.APPLICATION_JSON));

        // Same previewDir a preview-enabled call would write into, but constructed with previewEnabled=false
        // (the production default) — proves the directory stays untouched, not just that an unrelated dir does.
        new BrevoEmailService(builder, "test-key-123", "no-reply@setu.local", "Setu College", false, tempDir.toString())
            .sendPasswordResetEmail("student@example.com", "Ishaan", "http://localhost:5180/reset-password", "482916", Instant.now());

        server.verify();
        assertThat(isEmpty(tempDir)).isTrue();
    }

    @Test
    void writesTheRenderedEmailToALocalFileWhenPreviewIsEnabled(@TempDir Path tempDir) throws IOException {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andRespond(withSuccess("{\"messageId\":\"x\"}", MediaType.APPLICATION_JSON));

        previewingService("test-key-123", tempDir).sendPasswordResetEmail(
            "student@example.com", "Ishaan", "http://localhost:5180/reset-password", "482916",
            Instant.now().plus(30, ChronoUnit.MINUTES));

        server.verify();
        List<Path> written = Files.list(tempDir).toList();
        assertThat(written).hasSize(1);
        String content = Files.readString(written.get(0));
        assertThat(content).contains("482916").contains("http://localhost:5180/reset-password").contains("Ishaan");
    }

    @Test
    void stillWritesAPreviewFileEvenWhenNoApiKeyIsConfigured(@TempDir Path tempDir) throws IOException {
        // The office-network scenario this exists for may have no reachable Brevo at all — preview must not
        // depend on the real send succeeding, or even being attempted.
        EmailService.SendResult result = previewingService("", tempDir).sendPasswordResetEmail(
            "student@example.com", "Ishaan", "http://localhost:5180/reset-password", "731904",
            Instant.now().plus(30, ChronoUnit.MINUTES));

        assertThat(result.success()).isFalse();
        List<Path> written = Files.list(tempDir).toList();
        assertThat(written).hasSize(1);
        assertThat(Files.readString(written.get(0))).contains("731904");
    }

    @Test
    void previewFileNeverContainsTheApiKey(@TempDir Path tempDir) throws IOException {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andRespond(withSuccess("{\"messageId\":\"x\"}", MediaType.APPLICATION_JSON));

        previewingService("super-secret-api-key-value", tempDir).sendPasswordResetEmail(
            "student@example.com", "Ishaan", "http://localhost:5180/reset-password", "482916", Instant.now());

        server.verify();
        List<Path> written = Files.list(tempDir).toList();
        assertThat(Files.readString(written.get(0))).doesNotContain("super-secret-api-key-value");
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }
}
