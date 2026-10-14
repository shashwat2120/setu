package com.setu.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Prometheus metrics endpoint is neither public nor reachable with an ordinary user token: only the one dedicated scrape token
 * opens it, and it exposes both the platform metrics and the Setu business counters.
 */
@SpringBootTest(properties = "app.metrics.scrape-token=test-scrape-token-0123456789abcdef")
@AutoConfigureMockMvc
@AutoConfigureMetrics // Spring Boot switches metrics export off in tests; this test is about exactly that export
class MetricsEndpointSecurityTests {

    private static final String SCRAPE = "Bearer test-scrape-token-0123456789abcdef";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    com.setu.identity.application.UserRegistrationService registrationService;

    @Test
    void anonymousRequestsAreRefused() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongScrapeTokenIsRefused() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer not-the-scrape-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anOrdinaryUsersTokenIsAuthenticatedButForbidden() throws Exception {
        // A real, active account: an unknown or inactive user's token is refused earlier (401), see AccessTokenRevocationTests.
        UUID userId = ((com.setu.identity.application.UserRegistrationService.Outcome.Created)
            registrationService.register("metrics-" + UUID.randomUUID() + "@example.com")).userId();
        String userToken = "Bearer " + jwtService.issueAccessToken(userId);
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", userToken)).andExpect(status().isForbidden());
    }

    @Test
    void theScrapeTokenDoesNotOpenAnyOtherEndpoint() throws Exception {
        // On any other path the scrape token is just an invalid bearer token, so the caller stays anonymous.
        mockMvc.perform(get("/institution/colleges").header("Authorization", SCRAPE)).andExpect(status().isUnauthorized());
    }

    @Test
    void theScrapeTokenReadsPlatformAndBusinessMetrics() throws Exception {
        // A failed sign-in with a random address: counted by outcome only, never by who.
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"nobody-" + UUID.randomUUID() + "@example.com\",\"password\":\"Not-A-Real-Password-1!\"}"))
            .andExpect(status().isUnauthorized());

        String body = mockMvc.perform(get("/actuator/prometheus").header("Authorization", SCRAPE))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("application=\"setu-backend\"");
        assertThat(body).contains("setu_outbox_pending");
        assertThat(body).contains("setu_auth_logins_total{").contains("outcome=\"invalid_credentials\"");
        assertThat(body).doesNotContain("nobody-");
    }
}
