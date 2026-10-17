package com.setu.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.web.CorrelationIdFilter;
import com.setu.shared.web.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * What the public edge shows of its own management endpoints. The gateway has no Spring Security chain (it only forwards), and Spring Boot
 * serves {@code /actuator/*} itself, so without this every endpoint the application exposes would answer anyone who can reach the platform's
 * one public address: the metrics (route names, outcomes, request rates) and the module structure.
 * <ul>
 *   <li>{@code /actuator/health} (and its probes) and {@code /actuator/info}: public, as a load balancer needs;</li>
 *   <li>{@code /actuator/prometheus}: only with the dedicated scrape token, the same rule as every other service
 *       ({@code MetricsScrapeAuthFilter}), compared in constant time and closed to everyone when no token is configured;</li>
 *   <li>anything else under {@code /actuator}: not found, as if it did not exist here.</li>
 * </ul>
 */
public class ActuatorGuardFilter extends OncePerRequestFilter {

    private final byte[] scrapeToken;
    private final ObjectMapper objectMapper;

    public ActuatorGuardFilter(String scrapeToken, ObjectMapper objectMapper) {
        this.scrapeToken = scrapeToken == null || scrapeToken.isBlank() ? null : scrapeToken.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/actuator") || path.startsWith("/actuator/"));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/") || path.equals("/actuator/info")) {
            chain.doFilter(request, response);
        } else if (path.equals("/actuator/prometheus")) {
            if (presentsScrapeToken(request)) {
                chain.doFilter(request, response);
            } else {
                refuse(request, response, 401, "UNAUTHENTICATED", "authentication is required");
            }
        } else {
            refuse(request, response, 404, "NO_ROUTE", "no route for this path");
        }
    }

    private boolean presentsScrapeToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return scrapeToken != null && header != null && header.startsWith("Bearer ")
            && MessageDigest.isEqual(header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8), scrapeToken);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String correlationId = org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
        objectMapper.writeValue(response.getWriter(),
            ErrorResponse.of(status, code, message, request.getRequestURI(), correlationId != null ? correlationId : UUID.randomUUID().toString()));
    }
}
