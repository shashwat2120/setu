package com.setu.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.identity.application.LoginLivenessService;
import com.setu.shared.web.ErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Stateless bearer-token API security. No server-side HTTP session (Day 3
 * instruction: access token not long-lived in localStorage, refresh token in
 * a Secure+HttpOnly+SameSite cookie — neither needs a server session, the
 * refresh cookie IS the durable credential, looked up against
 * identity.sessions on each /auth/refresh call).
 * <p>
 * CSRF is disabled: this is a bearer-token JSON API, not form/cookie-session
 * auth, and the one cookie we do set (the refresh token) is SameSite=Strict
 * (see SessionService), which already blocks cross-site submission — the
 * classic CSRF vector CSRF-protection middleware exists for.
 * <p>
 * Authorization is coarse-grained on purpose: {@code /auth/**} is public
 * (that's the whole point — you can't require a token to get a token), health
 * checks are public, everything else requires *a* valid token. Real
 * role/resource authorization (RBAC, IDOR protection, tenant scoping) is
 * Day 4 — deliberately not started here per the Day 3 instruction.
 * <p>
 * Added Sprint 5D (post-Day 11 exception-handling audit): a custom {@code
 * authenticationEntryPoint} and {@code accessDeniedHandler}, so a request
 * this filter chain's own {@code .anyRequest().authenticated()} check
 * rejects gets the same JSON error shape as everything else instead of
 * Spring Security's unbranded default. See {@code
 * com.setu.shared.web.GlobalExceptionHandler}'s javadoc for why this is a
 * separate mechanism from that class rather than one more
 * {@code @ExceptionHandler} method there.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final WebAuthnProperties webAuthnProperties;
    private final ObjectMapper objectMapper;
    private final String metricsScrapeToken;
    private final LoginLivenessService loginLiveness;

    public SecurityConfig(JwtService jwtService, WebAuthnProperties webAuthnProperties, ObjectMapper objectMapper,
                          @Value("${app.metrics.scrape-token:}") String metricsScrapeToken, LoginLivenessService loginLiveness) {
        this.loginLiveness = loginLiveness;
        this.jwtService = jwtService;
        this.webAuthnProperties = webAuthnProperties;
        this.objectMapper = objectMapper;
        this.metricsScrapeToken = metricsScrapeToken;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(authenticationEntryPoint())
                .accessDeniedHandler(accessDeniedHandler()))
            .authorizeHttpRequests(authorize -> authorize
                // More specific patterns first — Spring Security matches in
                // order, and step-up (Day 4 §8) requires an already-valid
                // bearer token, unlike the rest of /auth/** (you can't
                // require a token to get your first token).
                .requestMatchers("/auth/step-up/**").authenticated()
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // Metrics describe the whole platform: only the dedicated Prometheus scrape token may read them (not public,
                // and not reachable with an ordinary user token either). See MetricsScrapeAuthFilter.
                .requestMatchers(MetricsScrapeAuthFilter.PATH).hasAuthority(MetricsScrapeAuthFilter.AUTHORITY)
                // Day 15 §9: the generated OpenAPI spec/UI itself is not sensitive (endpoint shapes only, no
                // data) — same reasoning as exposing /actuator/health publicly.
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // The WebSocket upgrade handshake is an ordinary HTTP GET, but a browser WebSocket client cannot
                // attach an Authorization header to it — that's why STOMP CONNECT carries the bearer token instead
                // (StompAuthorizationChannelInterceptor). The HTTP layer must let the handshake through; the real
                // authentication boundary is the CONNECT frame, enforced immediately after by that interceptor.
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(new MetricsScrapeAuthFilter(metricsScrapeToken), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtAuthenticationFilter(jwtService, loginLiveness), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Fires only for a request Spring Security's own {@code
     * .anyRequest().authenticated()} check rejects before any controller
     * method runs (no bearer token, or one JwtAuthenticationFilter
     * couldn't validate — see that filter's javadoc: either case simply
     * leaves the request unauthenticated, never throwing there). This runs
     * inside {@code ExceptionTranslationFilter}, entirely outside Spring
     * MVC's dispatch — {@code GlobalExceptionHandler}'s {@code
     * @RestControllerAdvice} never sees it, so the same {@link
     * ErrorResponse} shape is written directly here instead.
     */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> writeError(response, 401, "UNAUTHENTICATED", "authentication is required", request.getRequestURI());
    }

    /**
     * Fires only for the same coarse, filter-chain-level {@code
     * .anyRequest().authenticated()} check when the principal IS
     * authenticated but Spring Security's own authorization manager
     * denies the request outright (not used today — every real
     * permission/ownership/tenant check in this codebase happens inside
     * AuthorizationService, one layer further in, which
     * GlobalExceptionHandler's {@code AccessDeniedException} mapping
     * already covers) — kept for defense in depth and so this filter
     * chain never falls back to Spring Security's own unbranded default
     * handler.
     */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeError(response, 403, "ACCESS_DENIED", "you do not have permission to perform this operation", request.getRequestURI());
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, int status, String code, String message, String path) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Day 15 §7: reuses the request-scoped id com.setu.shared.web.CorrelationIdFilter put in MDC (that filter
        // runs before this filter chain, per its own Ordered.HIGHEST_PRECEDENCE) — falls back to a fresh one only
        // if that filter somehow never ran.
        String correlationId = org.slf4j.MDC.get(com.setu.shared.web.CorrelationIdFilter.MDC_KEY);
        ErrorResponse body = ErrorResponse.of(status, code, message, path, correlationId != null ? correlationId : UUID.randomUUID().toString());
        objectMapper.writeValue(response.getWriter(), body);
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(webAuthnProperties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true); // the refresh-token cookie must be sendable cross-origin (frontend/backend on different ports in dev)
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
