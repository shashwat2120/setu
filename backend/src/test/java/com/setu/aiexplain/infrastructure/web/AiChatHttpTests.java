package com.setu.aiexplain.infrastructure.web;

import com.setu.identity.infrastructure.security.JwtService;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 14 HTTP/controller tests — real Spring Security filter chain, real
 * PostgreSQL, real local Ollama, exercising {@code POST /ai/chat} end to
 * end.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiChatHttpTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    Day13TestFixtures day13Fixtures;

    private String bearerFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId);
    }

    @Test
    void studentChatsOverHttp_getsAStructuredAnswer() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("AIHTTP1");

        mockMvc.perform(post("/ai/chat")
                .header("Authorization", bearerFor(s.student().userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hello, what can you help me with?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").exists())
            .andExpect(jsonPath("$.correlationId").exists())
            .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void blankMessageOverHttp_returns400() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("AIHTTP2");

        mockMvc.perform(post("/ai/chat")
                .header("Authorization", bearerFor(s.student().userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedChatRequest_returns401() throws Exception {
        mockMvc.perform(post("/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hello\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
