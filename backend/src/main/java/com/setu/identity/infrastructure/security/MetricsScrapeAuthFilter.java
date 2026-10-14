package com.setu.identity.infrastructure.security;

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
 * Lets Prometheus read {@code /actuator/prometheus} with one dedicated scrape token ({@code app.metrics.scrape-token}), and nothing
 * else. Metrics are operational data about the whole platform, so the endpoint is neither public nor reachable with an ordinary user
 * token: only a request that presents exactly this token gets the {@value #AUTHORITY} authority the security rule asks for.
 * <p>
 * When no token is configured the endpoint stays closed to everyone (fail closed), and the comparison is constant-time so the token
 * cannot be guessed from response timing. The filter touches no other path.
 */
public class MetricsScrapeAuthFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "METRICS_SCRAPE";
    public static final String PATH = "/actuator/prometheus";

    private final byte[] expectedToken;

    public MetricsScrapeAuthFilter(String scrapeToken) {
        this.expectedToken = (scrapeToken == null || scrapeToken.isBlank()) ? null : scrapeToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (expectedToken != null && header != null && header.startsWith("Bearer ")) {
            byte[] presented = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(presented, expectedToken)) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "metrics-scraper", null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
            }
        }
        filterChain.doFilter(request, response);
    }
}
