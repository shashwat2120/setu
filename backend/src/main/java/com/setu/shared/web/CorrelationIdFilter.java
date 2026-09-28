package com.setu.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Day 15 §7: the HTTP-level correlation-id mechanism {@link
 * ErrorResponse}'s own javadoc previously disclosed as missing. Registered
 * via {@link CorrelationIdFilterConfig} (a {@code FilterRegistrationBean}
 * at the earliest order) rather than a plain {@code @Component} — a plain
 * component registration was found, empirically, to break {@code
 * @AutoConfigureMockMvc}'s own {@code MockMvcConfiguration} bean
 * (a {@code NullPointerException} inside its internal logger field) on
 * this Spring Boot version; wrapping the filter in an explicit {@code
 * FilterRegistrationBean} avoids whatever auto-detection path triggers
 * that, while still registering it for every real request AND for
 * MockMvc-driven tests (Spring Boot's test slice honors the application's
 * own {@code FilterRegistrationBean} beans the same way it would a plain
 * filter component).
 * <p>
 * Runs before Spring Security's own filter chain so the SAME id is
 * available even for a request Spring Security itself rejects (401
 * written by {@code SecurityConfig#authenticationEntryPoint}) — every
 * response this backend returns, success or failure, carries the same
 * {@code X-Correlation-Id} a client sent (or, if it sent none, one
 * generated here), and every log line for that request can be tied back
 * to it via MDC.
 * <p>
 * Deliberately the smallest useful piece: one id, one MDC key, one
 * response header. No distributed-tracing propagation (W3C traceparent,
 * OpenTelemetry span context) — that is Sprint 6's observability stack
 * (canonical spec §12's OpenTelemetry line), not Sprint 5's.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final int MAX_LENGTH = 100;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Never trusts a client-supplied value blindly into logs: bounded length, no control characters (log/header injection), alphanumeric plus a small punctuation set only — anything else is replaced with a freshly generated id. */
    private static String sanitize(String incoming) {
        if (incoming == null || incoming.isBlank() || incoming.length() > MAX_LENGTH
            || !incoming.matches("[A-Za-z0-9._-]+")) {
            return UUID.randomUUID().toString();
        }
        return incoming;
    }
}
