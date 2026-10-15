package com.setu.identity.application;

import com.setu.identity.infrastructure.security.JwtService;
import com.setu.shared.security.TokenRejectedException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Day 9 §8 cross-module port: {@code notification}'s WebSocket/STOMP
 * transport needs to authenticate a STOMP CONNECT frame's bearer token —
 * the same validation {@code JwtAuthenticationFilter} does for ordinary
 * HTTP requests, but WebSocket handshakes don't go through the servlet
 * filter chain, so {@code notification.infrastructure.websocket}'s channel
 * interceptor needs its own entry point. Wraps
 * {@code identity.infrastructure.security.JwtService} (never exposed
 * directly cross-module) and returns only a bare {@code UUID}, never the
 * full claims or the token itself.
 * <p>
 * {@code @NamedInterface} on this class (Day 9 §15 ceremony): caller is
 * {@code notification.infrastructure.websocket}'s STOMP CONNECT
 * interceptor. Only {@link #resolveUserId} is exposed. No reverse edge from
 * identity into notification exists. Verified via {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class TokenAuthenticationService implements com.setu.shared.websocket.StompTokenAuthenticator {

    private final JwtService jwtService;
    private final LoginLivenessService loginLiveness;

    public TokenAuthenticationService(JwtService jwtService, LoginLivenessService loginLiveness) {
        this.jwtService = jwtService;
        this.loginLiveness = loginLiveness;
    }

    @Override
    public Optional<UUID> resolveUserId(String bearerToken) {
        try {
            var claims = jwtService.validate(bearerToken);
            // Same rule as the HTTP filter: a revoked login or an inactive account is refused at STOMP CONNECT too.
            return loginLiveness.isLive(claims.userId(), claims.familyId()) ? Optional.of(claims.userId()) : Optional.empty();
        } catch (TokenRejectedException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
