package com.setu.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The platform's one public address must not hand out its management endpoints (docs/contracts/observability.md): health and info are public,
 * metrics need the scrape token, and nothing else under /actuator exists as far as a caller can tell. Everything outside /actuator is untouched.
 */
class ActuatorGuardFilterTests {

    @RestController
    static class Everything {
        @GetMapping({"/actuator/**", "/institution/colleges"})
        String answer() {
            return "served";
        }
    }

    private MockMvc with(String token) {
        return MockMvcBuilders.standaloneSetup(new Everything())
            .addFilters(new CorrelationIdFilter(), new ActuatorGuardFilter(token, new ObjectMapper().findAndRegisterModules())).build();
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString();
    }

    @Test
    void healthAndInfoArePublic() throws Exception {
        MockMvc mvc = with("scrape-token");
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info"}) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(200);
        }
    }

    @Test
    void metricsNeedTheScrapeTokenAndNothingElseWillDo() throws Exception {
        MockMvc mvc = with("scrape-token");

        assertThat(mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus()).as("no credential").isEqualTo(401);
        assertThat(mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer scrape-token ")).andReturn().getResponse().getStatus())
            .as("trailing space").isEqualTo(401);
        assertThat(mvc.perform(get("/actuator/prometheus").header("Authorization", "Basic c2NyYXBlLXRva2Vu")).andReturn().getResponse().getStatus())
            .as("not a bearer credential").isEqualTo(401);
        var refused = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse();
        assertThat(body(refused)).contains("UNAUTHENTICATED").doesNotContain("served");

        assertThat(mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer scrape-token")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void withNoTokenConfiguredMetricsAreClosedToEveryone() throws Exception {
        for (String token : new String[] {null, "", "  "}) {
            MockMvc mvc = with(token);
            assertThat(mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer ")).andReturn().getResponse().getStatus())
                .as("an empty bearer must not match an empty token").isEqualTo(401);
        }
    }

    @Test
    void everyOtherManagementEndpointDoesNotExistHere() throws Exception {
        MockMvc mvc = with("scrape-token");
        for (String path : new String[] {"/actuator", "/actuator/modulith", "/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/healthy",
            "/actuator/prometheus/extra", "/actuator/health-secret"}) {
            var response = mvc.perform(get(path).header("Authorization", "Bearer scrape-token")).andReturn().getResponse();
            assertThat(response.getStatus()).as(path + " even with the scrape token").isEqualTo(404);
            assertThat(body(response)).contains("NO_ROUTE").doesNotContain("served");
        }
    }

    @Test
    void everythingOutsideActuatorIsLeftToTheGateway() throws Exception {
        assertThat(with("scrape-token").perform(get("/institution/colleges")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
