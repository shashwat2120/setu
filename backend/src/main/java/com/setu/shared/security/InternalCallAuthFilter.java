package com.setu.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates a call from another Setu service to an {@code /internal/**} endpoint. These are the few synchronous questions one service
 * must put to another because the answer cannot be allowed to be seconds old (docs/contracts/data-ownership.md); they are never reachable
 * by a user token, and the gateway has no route to them, so they cannot be called from outside the platform at all.
 * <p>
 * The caller presents the shared internal token ({@code setu.internal.token}, delivered through the config server like any other secret) in
 * {@value #HEADER}, a header of its own so that it can never be confused with a user's bearer token. With no token configured every internal
 * call is refused (fail closed), the comparison is constant-time, and the filter touches no other path.
 */
public class InternalCallAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String AUTHORITY = "INTERNAL_SERVICE";
    public static final String PREFIX = "/internal/";

    private final byte[] expectedToken;

    public InternalCallAuthFilter(String token) {
        this.expectedToken = (token == null || token.isBlank()) ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (expectedToken != null && presented != null
            && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "internal-service", null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        }
        filterChain.doFilter(request, response);
    }
}
