package com.setu.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Day 15 §9: the OpenAPI spec is genuinely generated (not just configured) and publicly reachable without a token, matching every other purely-informational endpoint (health, info) in this codebase. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void openApiSpecIsPubliclyReachableAndDescribesRealEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").exists())
            .andExpect(jsonPath("$.info.title").value("Setu API"))
            .andExpect(jsonPath("$.paths['/ai/chat']").exists())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists());
    }

    @Test
    void swaggerUiIsPubliclyReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk());
    }
}
