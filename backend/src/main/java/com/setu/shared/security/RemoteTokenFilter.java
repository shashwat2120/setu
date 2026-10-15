package com.setu.shared.security;

import com.setu.shared.authorization.ServicePrincipal;
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
 * Bearer-token authentication for every service except Identity: verifies the token against Identity's published keys and the revocation
 * list ({@link RemoteTokenAuthenticator}) and, if both pass, attaches a {@link ServicePrincipal}. A missing, forged, expired or revoked
 * token leaves the request unauthenticated; the filter chain's own rules then refuse it. Roles and tenant are never read from the token.
 */
public class RemoteTokenFilter extends OncePerRequestFilter {

    private final RemoteTokenAuthenticator authenticator;

    public RemoteTokenFilter(RemoteTokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            authenticator.authenticate(header.substring("Bearer ".length())).ifPresent(claims -> {
                var principal = new ServicePrincipal(claims.userId(), claims.jti(), claims.stepUpAt(), claims.familyId());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
            });
        }
        filterChain.doFilter(request, response);
    }
}
