package com.setu.identity;

import com.setu.identity.domain.UserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test-only helper to populate the SecurityContext the way JwtAuthenticationFilter would, without going through a real HTTP request. */
public final class TestAuthentication {

    private TestAuthentication() {
    }

    public static void authenticateAs(UUID userId) {
        authenticateAs(userId, null);
    }

    public static void authenticateAs(UUID userId, Instant stepUpAt) {
        var principal = new UserPrincipal(userId, "test-jti-" + UUID.randomUUID(), stepUpAt);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
