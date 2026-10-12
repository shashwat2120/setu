package com.setu.identity.infrastructure.web;

import com.setu.identity.application.AuthenticationChallenge;
import com.setu.identity.application.PasskeyAuthOutcome;
import com.setu.identity.application.PasskeyAuthenticationService;
import com.setu.identity.application.PasskeyRegistrationService;
import com.setu.identity.application.RegistrationChallenge;
import com.setu.identity.application.RegistrationOutcome;
import com.setu.identity.application.SessionService;
import com.setu.identity.application.TokenPair;
import com.setu.identity.domain.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/auth/passkey")
public class PasskeyController {

    private final PasskeyRegistrationService registrationService;
    private final PasskeyAuthenticationService authenticationService;
    private final SessionService sessionService;
    private final RefreshCookieFactory refreshCookieFactory;

    public PasskeyController(PasskeyRegistrationService registrationService,
                              PasskeyAuthenticationService authenticationService, SessionService sessionService,
                              RefreshCookieFactory refreshCookieFactory) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    public record RegistrationOptionsRequest(UUID userId) {
    }

    public record RegistrationOptionsResponse(String challengeId, String challenge, String rpId, String rpName,
                                               String userHandle, String userName, long timeoutMillis,
                                               String residentKey) {
    }

    @PostMapping("/register/options")
    public ResponseEntity<?> registrationOptions(@RequestBody RegistrationOptionsRequest request,
                                                  java.security.Principal principal) {
        var result = registrationService.startRegistration(request.userId(), authenticatedUserId(principal));
        return switch (result) {
            case PasskeyRegistrationService.StartRegistrationResult.Started started -> {
                RegistrationChallenge c = started.challenge();
                yield ResponseEntity.ok(new RegistrationOptionsResponse(
                    c.challengeId().toString(), Base64Url.encode(c.challenge()), c.rpId(), c.rpName(),
                    Base64Url.encode(c.userHandle()), c.userEmail(), c.timeoutMillis(), c.residentKey()));
            }
            case PasskeyRegistrationService.StartRegistrationResult.NotAuthorized ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("not authorized to add a passkey to this account"));
        };
    }

    public record RegistrationFinishRequest(UUID userId, UUID challengeId, String registrationResponseJson) {
    }

    public record RegistrationFinishResponse(UUID passkeyId, String accessToken) {
    }

    @PostMapping("/register/finish")
    public ResponseEntity<?> registrationFinish(@RequestBody RegistrationFinishRequest request,
                                                 java.security.Principal principal,
                                                 jakarta.servlet.http.HttpServletResponse response) {
        var outcome = registrationService.finishRegistration(request.userId(), authenticatedUserId(principal),
            request.challengeId(), request.registrationResponseJson());
        return switch (outcome) {
            case RegistrationOutcome.Success success -> {
                TokenPair tokens = sessionService.startNewSession(request.userId(), null);
                response.addHeader("Set-Cookie", refreshCookieFactory.create(tokens.refreshToken()).toString());
                yield ResponseEntity.ok(new RegistrationFinishResponse(success.passkeyId(), tokens.accessToken()));
            }
            case RegistrationOutcome.ChallengeExpiredOrUnknown ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("challenge expired or unknown"));
            case RegistrationOutcome.CredentialAlreadyRegistered ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("credential already registered"));
            case RegistrationOutcome.VerificationFailed failed ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(failed.message()));
            case RegistrationOutcome.NotAuthorized ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("not authorized to add a passkey to this account"));
        };
    }

    public record AuthenticationOptionsRequest(String email) {
    }

    public record AuthenticationOptionsResponse(String challengeId, String challenge, String rpId,
                                                  List<String> allowCredentialIds, long timeoutMillis) {
    }

    @PostMapping("/authenticate/options")
    public ResponseEntity<AuthenticationOptionsResponse> authenticationOptions(
        @RequestBody AuthenticationOptionsRequest request) {
        AuthenticationChallenge c = authenticationService.startAuthentication(
            Optional.ofNullable(request.email()).filter(s -> !s.isBlank()));
        return ResponseEntity.ok(new AuthenticationOptionsResponse(
            c.challengeId().toString(), Base64Url.encode(c.challenge()), c.rpId(),
            c.allowCredentialIds().stream().map(Base64Url::encode).toList(), c.timeoutMillis()));
    }

    public record AuthenticationFinishRequest(UUID challengeId, String authenticationResponseJson) {
    }

    public record AuthenticationFinishResponse(String accessToken) {
    }

    @PostMapping("/authenticate/finish")
    public ResponseEntity<?> authenticationFinish(@RequestBody AuthenticationFinishRequest request,
                                                    jakarta.servlet.http.HttpServletResponse response) {
        var outcome = authenticationService.finishAuthentication(request.challengeId(),
            request.authenticationResponseJson());
        return switch (outcome) {
            case PasskeyAuthOutcome.Success success -> {
                TokenPair tokens = sessionService.startNewSession(success.userId(), null);
                response.addHeader("Set-Cookie", refreshCookieFactory.create(tokens.refreshToken()).toString());
                yield ResponseEntity.ok(new AuthenticationFinishResponse(tokens.accessToken()));
            }
            case PasskeyAuthOutcome.ChallengeExpiredOrUnknown ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("challenge expired or unknown"));
            case PasskeyAuthOutcome.UnknownCredential ignored ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("unknown credential"));
            case PasskeyAuthOutcome.RevokedCredential ignored ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("credential has been revoked"));
            case PasskeyAuthOutcome.VerificationFailed failed ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(failed.message()));
            case PasskeyAuthOutcome.AccountNotActive notActive -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(notActive.status() == com.setu.identity.domain.UserStatus.DEACTIVATED
                    ? "this account has been deactivated"
                    : "this account has been suspended"));
        };
    }

    public record PasskeyResponse(UUID id, boolean active) {
    }

    /** Lets a user see whether they already have a passkey before starting a new registration ceremony, and
     * gives the revoke UI something to list — {@link PasskeyAuthenticationService#listForUser} already existed
     * for the authentication-options lookup but had no HTTP surface of its own. */
    @org.springframework.web.bind.annotation.GetMapping
    public ResponseEntity<List<PasskeyResponse>> list(java.security.Principal principal) {
        UUID userId = authenticatedUserId(principal)
            .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("not authenticated"));
        return ResponseEntity.ok(authenticationService.listForUser(userId).stream()
            .map(p -> new PasskeyResponse(p.id(), p.isActive())).toList());
    }

    @DeleteMapping("/{passkeyId}")
    public ResponseEntity<?> revoke(@PathVariable UUID passkeyId, java.security.Principal principal) {
        UUID userId = authenticatedUserId(principal)
            .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("not authenticated"));
        return switch (authenticationService.revoke(userId, passkeyId)) {
            case REVOKED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case NOT_OWNED_BY_USER -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        };
    }

    private static Optional<UUID> authenticatedUserId(java.security.Principal principal) {
        if (principal instanceof org.springframework.security.authentication.UsernamePasswordAuthenticationToken token
            && token.getPrincipal() instanceof UserPrincipal userPrincipal) {
            return Optional.of(userPrincipal.userId());
        }
        return Optional.empty();
    }
}
