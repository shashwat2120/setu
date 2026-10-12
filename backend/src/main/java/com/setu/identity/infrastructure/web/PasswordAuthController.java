package com.setu.identity.infrastructure.web;

import com.setu.identity.application.PasswordAuthOutcome;
import com.setu.identity.application.PasswordAuthenticationService;
import com.setu.identity.application.PasswordResetService;
import com.setu.identity.application.SessionService;
import com.setu.identity.application.TokenPair;
import com.setu.identity.infrastructure.security.AuthRateLimiter;
import com.setu.shared.governance.AuditLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class PasswordAuthController {

    private final PasswordAuthenticationService passwordAuthenticationService;
    private final PasswordResetService passwordResetService;
    private final SessionService sessionService;
    private final RefreshCookieFactory refreshCookieFactory;

    /**
     * No email delivery is wired up yet (see PasswordResetService javadoc) —
     * exposing the raw reset token directly in the HTTP response is a
     * dev-only stand-in so the endpoint is actually usable/testable before
     * the notification module exists. MUST be false the moment real email
     * delivery is wired up; default true only because the alternative today
     * is a reset flow nobody can complete.
     */
    @Value("${app.security.password-reset.expose-token-for-dev:false}")
    private boolean exposeResetTokenForDev;

    /** Only true behind a reverse proxy/gateway that overwrites X-Forwarded-For; otherwise any caller could dodge the per-IP limit by forging it. */
    @Value("${app.security.rate-limit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    private final AuthRateLimiter authRateLimiter;
    private final AuditLogWriter auditLogWriter;

    public PasswordAuthController(PasswordAuthenticationService passwordAuthenticationService,
                                   PasswordResetService passwordResetService, SessionService sessionService,
                                   RefreshCookieFactory refreshCookieFactory, AuthRateLimiter authRateLimiter,
                                   AuditLogWriter auditLogWriter) {
        this.authRateLimiter = authRateLimiter;
        this.auditLogWriter = auditLogWriter;
        this.passwordAuthenticationService = passwordAuthenticationService;
        this.passwordResetService = passwordResetService;
        this.sessionService = sessionService;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String accessToken) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        var limit = authRateLimiter.checkLogin(request.email(), clientIp(httpRequest));
        if (!limit.allowed()) {
            return tooManyRequests(limit, "LOGIN_RATE_LIMITED", request.email(), httpRequest, response);
        }
        var outcome = passwordAuthenticationService.authenticate(request.email(), request.password());
        return switch (outcome) {
            case PasswordAuthOutcome.Success success -> {
                TokenPair tokens = sessionService.startNewSession(success.userId(), null);
                response.addHeader("Set-Cookie", refreshCookieFactory.create(tokens.refreshToken()).toString());
                yield ResponseEntity.ok(new LoginResponse(tokens.accessToken()));
            }
            case PasswordAuthOutcome.InvalidCredentials ignored ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("invalid email or password"));
            case PasswordAuthOutcome.AccountLocked ignored -> ResponseEntity.status(HttpStatus.LOCKED)
                .body(new ErrorResponse("account temporarily locked after repeated failed attempts"));
            case PasswordAuthOutcome.NoPasswordConfigured ignored -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("invalid email or password"));
            case PasswordAuthOutcome.AccountNotActive notActive -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(notActive.status() == com.setu.identity.domain.UserStatus.DEACTIVATED
                    ? "this account has been deactivated"
                    : "this account has been suspended"));
        };
    }

    /**
     * Same body for login and reset so a limited reset request reveals nothing about whether the email exists
     * (the limit is per email AND per IP and applies equally to known and unknown addresses). Audited once per
     * window, with only a short hash of the email, so a flood of refused requests cannot fill the audit table.
     */
    private ResponseEntity<?> tooManyRequests(AuthRateLimiter.Decision limit, String auditAction, String email,
                                               HttpServletRequest httpRequest, HttpServletResponse response) {
        if (limit.firstBlock()) {
            auditLogWriter.write(null, null, auditAction, "identity.users", null,
                Map.of("emailHash", AuthRateLimiter.shortHash(email), "ip", String.valueOf(clientIp(httpRequest))));
        }
        response.setHeader("Retry-After", String.valueOf(limit.retryAfterSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(new ErrorResponse("too many attempts, try again in " + limit.retryAfterSeconds() + " seconds"));
    }

    /** The TCP peer address. X-Forwarded-For is honored only when explicitly enabled, because any client can forge it. */
    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    public record ResetInitiateRequest(String email) {
    }

    public record ResetInitiateResponse(String devOnlyResetToken) {
    }

    @PostMapping("/password/reset/initiate")
    public ResponseEntity<?> resetInitiate(@RequestBody ResetInitiateRequest request, HttpServletRequest httpRequest,
                                           HttpServletResponse response) {
        var limit = authRateLimiter.checkPasswordReset(request.email(), clientIp(httpRequest));
        if (!limit.allowed()) {
            return tooManyRequests(limit, "PASSWORD_RESET_RATE_LIMITED", request.email(), httpRequest, response);
        }
        var rawToken = passwordResetService.initiateAndEmail(request.email());
        // Same 200 response whether the email exists or not — a reset endpoint
        // that answers differently for known vs. unknown emails is a
        // user-enumeration oracle.
        String exposed = (exposeResetTokenForDev && rawToken.isPresent()) ? rawToken.get() : null;
        return ResponseEntity.ok(new ResetInitiateResponse(exposed));
    }

    public record ResetCompleteRequest(String token, String newPassword) {
    }

    @PostMapping("/password/reset/complete")
    public ResponseEntity<?> resetComplete(@RequestBody ResetCompleteRequest request) {
        var result = passwordResetService.complete(request.token(), request.newPassword());
        return switch (result) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case INVALID_OR_EXPIRED_TOKEN ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid or expired reset token"));
            // A distinct message so the page can say "choose a stronger password" instead of "your link expired".
            case WEAK_PASSWORD -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
                "password_too_weak: use " + com.setu.identity.domain.PasswordPolicy.MIN_LENGTH + " to "
                    + com.setu.identity.domain.PasswordPolicy.MAX_LENGTH + " characters, and avoid a very common password or your email address"));
        };
    }
}
