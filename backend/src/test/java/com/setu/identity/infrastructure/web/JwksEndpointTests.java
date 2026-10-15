package com.setu.identity.infrastructure.web;

import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.shared.security.Jwks;
import com.setu.shared.security.JwksKeySource;
import com.setu.shared.security.KeyedTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract the gateway and the other services rely on: Identity publishes its public keys unauthenticated, and a verifier that knows
 * nothing but that document accepts exactly the tokens Identity issues.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwksEndpointTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    UserRegistrationService registrationService;

    @Test
    void thePublicKeySetIsReachableWithoutAToken() throws Exception {
        String body = mockMvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/jwk-set+json")))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")))
            .andReturn().getResponse().getContentAsString();

        assertThat(Jwks.parse(body)).isNotEmpty();
        assertThat(body).doesNotContain("\"d\"");
    }

    @Test
    void aServiceThatKnowsOnlyTheJwksAcceptsTokensIdentityIssues() throws Exception {
        UUID userId = ((UserRegistrationService.Outcome.Created) registrationService.register("jwks-" + UUID.randomUUID() + "@example.com")).userId();
        String token = jwtService.issueAccessToken(userId);
        String jwks = mockMvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getContentAsString();

        KeyedTokenVerifier remote = new KeyedTokenVerifier(
            new JwksKeySource(() -> jwks, Clock.systemUTC(), Duration.ofMinutes(10), Duration.ofSeconds(30)), "setu", "setu-api");

        assertThat(remote.verify(token).userId()).isEqualTo(userId);
    }

    @Test
    void theEndpointDoesNotOpenAnythingElseToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/.well-known/other")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/institution/colleges")).andExpect(status().isUnauthorized());
    }
}
