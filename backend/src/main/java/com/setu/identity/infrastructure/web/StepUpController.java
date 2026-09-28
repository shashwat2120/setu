package com.setu.identity.infrastructure.web;

import com.setu.identity.application.PasskeyAuthOutcome;
import com.setu.identity.application.PasskeyAuthenticationService;
import com.setu.identity.domain.UserPrincipal;
import com.setu.identity.infrastructure.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;

/**
 * Step-up reauthentication (Day 4 instruction §8): an already-authenticated
 * user proves they still hold their passkey, right now, before performing a
 * sensitive operation (role changes, passkey management, emergency
 * suspension, ...). Requires an existing valid bearer token — this is NOT a
 * login path, it's a freshness proof layered on top of one.
 */
@RestController
@RequestMapping("/auth/step-up")
public class StepUpController {

    private final PasskeyAuthenticationService authenticationService;
    private final JwtService jwtService;

    public StepUpController(PasskeyAuthenticationService authenticationService, JwtService jwtService) {
        this.authenticationService = authenticationService;
        this.jwtService = jwtService;
    }

    public record StepUpOptionsResponse(String challengeId, String challenge, String rpId, long timeoutMillis) {
    }

    @PostMapping("/options")
    public ResponseEntity<StepUpOptionsResponse> options(Principal principal) {
        var userId = requireUserId(principal);
        var challenge = authenticationService.startStepUpAuthentication(userId);
        return ResponseEntity.ok(new StepUpOptionsResponse(
            challenge.challengeId().toString(), Base64Url.encode(challenge.challenge()), challenge.rpId(),
            challenge.timeoutMillis()));
    }

    public record StepUpFinishRequest(java.util.UUID challengeId, String authenticationResponseJson) {
    }

    public record StepUpFinishResponse(String accessToken) {
    }

    @PostMapping("/finish")
    public ResponseEntity<?> finish(@RequestBody StepUpFinishRequest request, Principal principal) {
        var userId = requireUserId(principal);
        var outcome = authenticationService.finishAuthentication(request.challengeId(), request.authenticationResponseJson());
        return switch (outcome) {
            case PasskeyAuthOutcome.Success success -> {
                if (!success.userId().equals(userId)) {
                    // The challenge was scoped to `userId` already (see
                    // startStepUpAuthentication), so this branch is
                    // defense-in-depth, not the primary enforcement.
                    yield ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("credential does not belong to the authenticated account"));
                }
                String token = jwtService.issueAccessToken(userId, Instant.now());
                yield ResponseEntity.ok(new StepUpFinishResponse(token));
            }
            case PasskeyAuthOutcome.ChallengeExpiredOrUnknown ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("challenge expired or unknown"));
            case PasskeyAuthOutcome.UnknownCredential ignored ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("unknown credential"));
            case PasskeyAuthOutcome.RevokedCredential ignored ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("credential has been revoked"));
            case PasskeyAuthOutcome.VerificationFailed failed ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(failed.message()));
        };
    }

    private static java.util.UUID requireUserId(Principal principal) {
        if (principal instanceof org.springframework.security.authentication.UsernamePasswordAuthenticationToken token
            && token.getPrincipal() instanceof UserPrincipal userPrincipal) {
            return userPrincipal.userId();
        }
        throw new AccessDeniedException("not authenticated");
    }
}
