package com.setu.identity.infrastructure.web;

import com.setu.identity.application.PasswordAuthOutcome;
import com.setu.identity.application.PasswordAuthenticationService;
import com.setu.identity.application.PasswordResetService;
import com.setu.identity.application.SessionService;
import com.setu.identity.application.TokenPair;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public PasswordAuthController(PasswordAuthenticationService passwordAuthenticationService,
                                   PasswordResetService passwordResetService, SessionService sessionService,
                                   RefreshCookieFactory refreshCookieFactory) {
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
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletResponse response) {
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
        };
    }

    public record ResetInitiateRequest(String email) {
    }

    public record ResetInitiateResponse(String devOnlyResetToken) {
    }

    @PostMapping("/password/reset/initiate")
    public ResponseEntity<ResetInitiateResponse> resetInitiate(@RequestBody ResetInitiateRequest request) {
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
        };
    }
}
