package com.setu.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.web.CorrelationIdFilter;
import com.setu.shared.web.ErrorResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * How every service except Identity authenticates a request (Sprint 6A.2 contract, docs/contracts/identity-service.md): verify the bearer
 * token against Identity's published keys, then check the revocation list. No secret, no database, no call to Identity per request.
 * Active only when {@code setu.auth.mode=remote}, which each extracted service's entry point sets; the monolith and Identity itself never
 * load it.
 * <p>
 * The filter chain mirrors Identity's: stateless, tokens only, JSON error bodies, health probes public. CORS is the gateway's concern
 * (6A.4), not each service's.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "setu.auth.mode", havingValue = "remote")
@Import(RemoteVerificationConfig.class)
public class RemoteAuthenticationConfig {

    @Bean
    SecurityFilterChain remoteSecurityFilterChain(HttpSecurity http, RemoteTokenAuthenticator authenticator, ObjectMapper objectMapper,
                                                  @org.springframework.beans.factory.annotation.Value("${setu.internal.token:}") String internalToken,
                                                  @org.springframework.beans.factory.annotation.Value("${app.metrics.scrape-token:}") String metricsScrapeToken) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, e) ->
                    writeError(objectMapper, response, 401, "UNAUTHENTICATED", "authentication is required", request.getRequestURI()))
                .accessDeniedHandler((request, response, e) ->
                    writeError(objectMapper, response, 403, "ACCESS_DENIED", "you do not have permission to perform this operation", request.getRequestURI())))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // Prometheus reads every service's metrics with the one dedicated scrape token and nothing else (MetricsScrapeAuthFilter): never
                // public, never reachable with a user's token; closed to everyone when no token is configured.
                .requestMatchers(MetricsScrapeAuthFilter.PATH).hasAuthority(MetricsScrapeAuthFilter.AUTHORITY)
                // every other management endpoint (the module structure, ...) is for nobody: no user token opens it
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // service-to-service questions (docs/contracts/data-ownership.md): only the internal token, never a user's
                .requestMatchers(InternalCallAuthFilter.PREFIX + "**").hasAuthority(InternalCallAuthFilter.AUTHORITY)
                .anyRequest().authenticated())
            .addFilterBefore(new MetricsScrapeAuthFilter(metricsScrapeToken), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new InternalCallAuthFilter(internalToken), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new RemoteTokenFilter(authenticator), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(ObjectMapper objectMapper, jakarta.servlet.http.HttpServletResponse response, int status, String code,
                                   String message, String path) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String correlationId = org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
        objectMapper.writeValue(response.getWriter(),
            ErrorResponse.of(status, code, message, path, correlationId != null ? correlationId : UUID.randomUUID().toString()));
    }
}
