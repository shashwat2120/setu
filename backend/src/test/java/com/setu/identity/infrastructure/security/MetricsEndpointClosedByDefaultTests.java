package com.setu.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With no scrape token configured the metrics endpoint stays closed to everyone: it fails closed, never open. */
@SpringBootTest(properties = "app.metrics.scrape-token=")
@AutoConfigureMockMvc
class MetricsEndpointClosedByDefaultTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void noConfiguredTokenMeansNoOneCanReadMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer anything")).andExpect(status().isUnauthorized());
    }
}
