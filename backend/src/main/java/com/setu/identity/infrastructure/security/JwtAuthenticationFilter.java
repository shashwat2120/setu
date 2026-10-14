package com.setu.identity.infrastructure.security;

import com.setu.identity.application.LoginLivenessService;
import com.setu.identity.domain.UserPrincipal;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Stateless bearer-token authentication: reads Authorization: Bearer, validates
 * it (signature/issuer/audience/expiry — see JwtService), and populates the
 * SecurityContext. No roles/authorities attached yet — that's Day 4. An
 * invalid or missing token simply leaves the request unauthenticated; it's
 * up to endpoint-level authorization (also Day 4) to reject anonymous access
 * where required. A malformed/expired token never throws past this filter —
 * it's treated the same as "no token", not a 500.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final LoginLivenessService loginLiveness;

    public JwtAuthenticationFilter(JwtService jwtService, LoginLivenessService loginLiveness) {
        this.jwtService = jwtService;
        this.loginLiveness = loginLiveness;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            try {
                JwtService.AccessTokenClaims claims = jwtService.validate(token);
                // A correctly signed, unexpired token still counts only while its login is live and its account active.
                if (loginLiveness.isLive(claims.userId(), claims.familyId())) {
                    UserPrincipal principal = new UserPrincipal(claims.userId(), claims.jti(), claims.stepUpAt(), claims.familyId());
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // No token, an expired one, or a forged one — all leave the request unauthenticated.
            }
        }
        filterChain.doFilter(request, response);
    }
}
