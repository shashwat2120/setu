package com.setu.identity.infrastructure.email;

import com.setu.identity.application.email.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

import static com.setu.identity.infrastructure.email.PasswordResetEmailContent.maskEmail;

/**
 * Sends the reset email over plain SMTP, for example through Gmail ({@code smtp.gmail.com:587}, a Google "app password"). Active when
 * {@code app.email.provider=smtp}; {@link BrevoEmailService} is the default alternative. The email itself is identical to Brevo's
 * ({@link PasswordResetEmailContent}).
 * <p>
 * JavaMail uses ordinary blocking sockets, which is the one socket style that works on this dev machine (see {@link BrevoEmailService}).
 * The send runs inside the reset request's transaction, so every phase has a short timeout: a slow mail server delays the answer by
 * seconds at most and never holds the database connection for long. The SMTP password is read once from configuration, handed only
 * to the mail session, and never logged or placed in any message built here. A failed send is reported as a failed {@link SendResult},
 * never thrown, so what the caller shows the visitor does not depend on delivery.
 */
@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "smtp")
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);
    private static final int TIMEOUT_MILLIS = 10_000;

    private final JavaMailSender mailSender;
    private final boolean configured;
    private final String fromAddress;
    private final String fromName;
    private final boolean previewEnabled;
    private final Path previewDir;

    @Autowired
    public SmtpEmailService(@Value("${app.email.smtp.host:smtp.gmail.com}") String host,
                            @Value("${app.email.smtp.port:587}") int port,
                            @Value("${app.email.smtp.username:}") String username,
                            @Value("${app.email.smtp.password:}") String password,
                            @Value("${app.email.smtp.from-address:}") String fromAddress,
                            @Value("${app.email.smtp.from-name:Setu College}") String fromName,
                            @Value("${app.email.preview.enabled:false}") boolean previewEnabled,
                            @Value("${app.email.preview.dir:target/demo-email-outbox}") String previewDir) {
        this(buildSender(host, port, username, password), !username.isBlank() && !password.isBlank(),
            fromAddress.isBlank() ? username : fromAddress, fromName, previewEnabled, previewDir);
    }

    /** For tests: supply the sender directly. */
    SmtpEmailService(JavaMailSender mailSender, boolean configured, String fromAddress, String fromName,
                     boolean previewEnabled, String previewDir) {
        this.mailSender = mailSender;
        this.configured = configured;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.previewEnabled = previewEnabled;
        this.previewDir = Path.of(previewDir);
    }

    private static JavaMailSender buildSender(String host, int port, String username, String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            // Never send the password over an unencrypted connection: refuse to continue if the server does not offer STARTTLS.
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MILLIS));
        return sender;
    }

    @Override
    public SendResult sendPasswordResetEmail(String toEmail, String displayName, String resetUrl, String code, Instant expiresAt) {
        String greeting = PasswordResetEmailContent.greeting(displayName);
        String expiryText = PasswordResetEmailContent.expiryText(expiresAt);
        String html = PasswordResetEmailContent.htmlBody(greeting, resetUrl, code, expiryText);

        if (previewEnabled) {
            PasswordResetEmailContent.writePreview(log, previewDir, toEmail, resetUrl, expiryText, html);
        }

        if (!configured || fromAddress.isBlank()) {
            log.warn("Password reset email not sent to {}: SMTP is not configured (app.email.smtp.username / SMTP_USER and SMTP_PASSWORD)",
                maskEmail(toEmail));
            return SendResult.failure("email provider not configured");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(toEmail);
            helper.setSubject(PasswordResetEmailContent.SUBJECT);
            helper.setText(PasswordResetEmailContent.textBody(greeting, resetUrl, code, expiryText), html);
            mailSender.send(message);
            return SendResult.success(message.getMessageID());
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.warn("Password reset email delivery failed for {}: {}: {}", maskEmail(toEmail), e.getClass().getSimpleName(), e.getMessage());
            return SendResult.failure("email delivery failed");
        }
    }
}
