package com.setu.identity.infrastructure.email;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.setu.identity.application.email.EmailService;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SMTP sender builds the reset email and never lets a delivery problem escape or leak the password. A
 * recording {@link JavaMailSenderImpl} stands in for the mail server, so no network call is made except in the one test that
 * deliberately connects to a closed local port.
 */
class SmtpEmailServiceTests {

    private static final String PASSWORD = "s3cret-app-password";
    private static final Instant EXPIRES = Instant.now().plus(15, ChronoUnit.MINUTES);

    /** Records what would have been handed to the mail server; optionally refuses like a server rejecting the login. */
    private static final class RecordingSender extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new ArrayList<>();
        boolean rejectLogin;

        @Override
        protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
            if (rejectLogin) {
                throw new MailAuthenticationException("535 5.7.8 Username and Password not accepted");
            }
            for (MimeMessage message : mimeMessages) {
                try {
                    message.saveChanges(); // what the real transport does: assigns the Message-ID
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                sent.add(message);
            }
        }
    }

    private ListAppender<ILoggingEvent> logAppender;
    private final RecordingSender sender = new RecordingSender();

    @BeforeEach
    void captureLogs() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(SmtpEmailService.class)).addAppender(logAppender);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(SmtpEmailService.class)).detachAppender(logAppender);
    }

    private SmtpEmailService service(boolean configured, String fromAddress, boolean preview, Path previewDir) {
        return new SmtpEmailService(sender, configured, fromAddress, "Setu College", preview, previewDir.toString());
    }

    private static String raw(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString("UTF-8");
    }

    @Test
    void sendsOneMultipartMessageFromTheConfiguredAddressWithTheCodeAndThePageLink(@TempDir Path dir) throws Exception {
        EmailService.SendResult result = service(true, "sender@gmail.com", false, dir)
            .sendPasswordResetEmail("student@college.edu", "Asha", "http://localhost:5180/reset-password", "062251", EXPIRES);

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isNotBlank();
        assertThat(sender.sent).hasSize(1);
        MimeMessage message = sender.sent.get(0);
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("sender@gmail.com");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Setu College");
        assertThat(message.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress()).isEqualTo("student@college.edu");
        assertThat(message.getSubject()).isEqualTo("Your Setu College password reset code");
        String raw = raw(message);
        assertThat(raw).contains("Your reset code: 062251").contains("multipart/alternative").contains("text/html");
        assertThat(raw).contains("http://localhost:5180/reset-password");
        // The link carries no secret: the code is the only secret and it is not part of any URL.
        assertThat(raw).doesNotContain("reset-password?");
    }

    @Test
    void notConfiguredReportsFailureWithoutSendingOrThrowing(@TempDir Path dir) {
        EmailService.SendResult result = service(false, "", false, dir)
            .sendPasswordResetEmail("student@college.edu", "Asha", "http://localhost:5180/reset-password", "062251", EXPIRES);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("email provider not configured");
        assertThat(sender.sent).isEmpty();
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage().contains("SMTP is not configured"));
    }

    @Test
    void aRejectedLoginIsReportedAsAFailureNotThrownAndTheAddressIsMaskedInTheLog(@TempDir Path dir) {
        sender.rejectLogin = true;

        EmailService.SendResult result = service(true, "sender@gmail.com", false, dir)
            .sendPasswordResetEmail("student@college.edu", "Asha", "http://localhost:5180/reset-password", "062251", EXPIRES);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("email delivery failed");
        String logged = String.join("\n", logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(logged).contains("delivery failed").contains("s***@college.edu");
        assertThat(logged).doesNotContain("student@college.edu").doesNotContain("062251");
    }

    @Test
    void theLocalPreviewFileIsWrittenEvenWhenSendingFails(@TempDir Path dir) throws Exception {
        sender.rejectLogin = true;

        service(true, "sender@gmail.com", true, dir)
            .sendPasswordResetEmail("student@college.edu", "Asha", "http://localhost:5180/reset-password", "062251", EXPIRES);

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> written = files.toList();
            assertThat(written).hasSize(1);
            assertThat(Files.readString(written.get(0))).contains("062251");
        }
    }

    @Test
    void anAddressWithALineBreakInItIsRefusedNotInjectedIntoTheHeaders(@TempDir Path dir) {
        EmailService.SendResult result = service(true, "sender@gmail.com", false, dir)
            .sendPasswordResetEmail("a@b.com\r\nBcc: attacker@evil.example", "Asha", "http://localhost:5180/reset-password", "062251", EXPIRES);

        assertThat(result.success()).isFalse();
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void theRealPathDefaultsTheSenderToTheUsernameAndNeverLogsThePasswordWhenTheServerIsUnreachable(@TempDir Path dir) {
        // Port 1 on this machine refuses the connection immediately, so this exercises the real JavaMail session without a network.
        SmtpEmailService real = new SmtpEmailService("127.0.0.1", 1, "sender@gmail.com", PASSWORD, "", "Setu College", false, dir.toString());

        EmailService.SendResult result = real.sendPasswordResetEmail(
            "student@college.edu", "Asha", "http://localhost:5180/reset-password", "062251", EXPIRES);

        assertThat(result.success()).isFalse();
        String logged = String.join("\n", logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(logged).contains("delivery failed").doesNotContain(PASSWORD);
    }

}
