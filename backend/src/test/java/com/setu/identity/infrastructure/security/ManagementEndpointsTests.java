package com.setu.identity.infrastructure.security;

import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What the application's management endpoints show, and to whom (docs/contracts/observability.md): health and info to everyone, metrics to the
 * scrape token alone, and everything else (the module structure, the environment, bean lists ...) to nobody, whoever they are and whatever token
 * they hold. A user's valid token used to be enough to read the module structure.
 */
@SpringBootTest(properties = "app.metrics.scrape-token=scrape-test-token")
@AutoConfigureMockMvc
@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics   // Spring Boot tests switch metric export off; the scrape endpoint needs it on
class ManagementEndpointsTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    Day7TestFixtures fixtures;

    private int status(String path, String bearer) throws Exception {
        var request = get(path);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void healthIsPublicAndMetricsNeedTheScrapeToken() throws Exception {
        assertThat(status("/actuator/health", null)).as("reachable without credentials (503 only if a dependency is down)").isIn(200, 503);
        assertThat(status("/actuator/prometheus", null)).isEqualTo(401);
        assertThat(status("/actuator/prometheus", "wrong")).isEqualTo(401);
        assertThat(status("/actuator/prometheus", "scrape-test-token")).isEqualTo(200);
    }

    @Test
    void everyOtherManagementEndpointIsClosedToEveryoneIncludingAdministratorsAndTheScraper() throws Exception {
        String admin = jwtService.issueAccessToken(fixtures.provisionTenant("MGT1").adminUserId());

        for (String path : new String[] {"/actuator", "/actuator/modulith", "/actuator/env", "/actuator/beans", "/actuator/heapdump"}) {
            assertThat(status(path, null)).as(path + " anonymous").isEqualTo(401);
            assertThat(status(path, admin)).as(path + " with a college administrator's token").isIn(403, 404);
            assertThat(status(path, "scrape-test-token")).as(path + " with the scrape token").isIn(401, 403, 404);
        }
    }
}
