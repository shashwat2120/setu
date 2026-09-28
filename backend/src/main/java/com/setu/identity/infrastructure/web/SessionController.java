package com.setu.identity.infrastructure.web;

import com.setu.identity.application.RefreshOutcome;
import com.setu.identity.application.SessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class SessionController {

    private final SessionService sessionService;
    private final RefreshCookieFactory refreshCookieFactory;

    public SessionController(SessionService sessionService, RefreshCookieFactory refreshCookieFactory) {
        this.sessionService = sessionService;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    public record RefreshResponse(String accessToken) {
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {
        Optional<String> rawRefreshToken = readRefreshCookie(request);
        if (rawRefreshToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("no refresh token presented"));
        }
        var outcome = sessionService.refresh(rawRefreshToken.get(), null);
        return switch (outcome) {
            case RefreshOutcome.Rotated rotated -> {
                response.addHeader("Set-Cookie",
                    refreshCookieFactory.create(rotated.tokens().refreshToken()).toString());
                yield ResponseEntity.ok(new RefreshResponse(rotated.tokens().accessToken()));
            }
            case RefreshOutcome.Invalid invalid -> {
                response.addHeader("Set-Cookie", refreshCookieFactory.clear().toString());
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(invalid.reason()));
            }
            case RefreshOutcome.ReuseDetected reuse -> {
                response.addHeader("Set-Cookie", refreshCookieFactory.clear().toString());
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("refresh token reuse detected — all sessions revoked, please sign in again"));
            }
        };
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        readRefreshCookie(request).ifPresent(sessionService::logout);
        response.addHeader("Set-Cookie", refreshCookieFactory.clear().toString());
        return ResponseEntity.noContent().build();
    }

    private static Optional<String> readRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (RefreshCookieFactory.COOKIE_NAME.equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
