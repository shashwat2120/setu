package com.setu.risk.infrastructure.web;

import com.setu.identity.TestAuthentication;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.risk.application.RecommendationService;
import com.setu.risk.application.RiskCalculationService;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 13 HTTP/controller tests — real Spring Security filter chain, real
 * PostgreSQL, exercising {@code RiskScoreController}/{@code
 * RecommendationController}/{@code InterventionController} end to end,
 * including the 401/403 paths through {@code GlobalExceptionHandler}'s
 * existing status mapping.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RiskHttpTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    RiskCalculationService riskCalculationService;
    @Autowired
    RecommendationService recommendationService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private String bearerFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId);
    }

    @Test
    void advisorRecalculatesOverHttp_thenStudentReadsItsOwnScore() throws Exception {
        var s = fixtures.provisionEnrolledScenario("RH1");

        mockMvc.perform(post("/risk/scores/recalculate")
                .header("Authorization", bearerFor(s.faculty().userId()))
                .param("studentId", s.student().studentId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.band").value("LOW"));

        mockMvc.perform(get("/risk/scores/current")
                .header("Authorization", bearerFor(s.student().userId()))
                .param("studentId", s.student().studentId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.band").value("LOW"))
            .andExpect(jsonPath("$.ruleVersion").value("v1"))
            .andExpect(jsonPath("$.factors").isArray());
    }

    @Test
    void studentCannotReadAnotherStudentsScoreOverHttp() throws Exception {
        var s = fixtures.provisionEnrolledScenario("RH2");
        var other = fixtures.provisionEnrolledScenario("RH2B");
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        try {
            riskCalculationService.recalculate(s.student().studentId());
        } finally {
            TestAuthentication.clear();
        }

        mockMvc.perform(get("/risk/scores/current")
                .header("Authorization", bearerFor(other.student().userId()))
                .param("studentId", s.student().studentId().toString()))
            .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestToRisk_returns401() throws Exception {
        mockMvc.perform(get("/risk/scores/current").param("studentId", UUID.randomUUID().toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void acceptingARecommendationOverHttp_createsAnIntervention() throws Exception {
        var s = fixtures.provisionEnrolledScenario("RH3");
        fixtures.recordAttendanceSession(s, false);
        fixtures.recordGrade(s, "F", BigDecimal.ZERO);
        fixtures.recordSubmission(s, -60);
        fixtures.recordLearningProgress(s, com.setu.learning.domain.LearningProgressState.IN_PROGRESS);

        TestAuthentication.authenticateAs(s.faculty().userId());
        UUID riskScoreId;
        try {
            var result = (RiskCalculationService.RecalculateResult.Success) riskCalculationService.recalculate(s.student().studentId());
            riskScoreId = result.riskScoreId();
        } finally {
            TestAuthentication.clear();
        }
        TestAuthentication.authenticateAs(s.faculty().userId());
        UUID recommendationId;
        try {
            recommendationId = recommendationService.listForRiskScore(riskScoreId).get(0).id();
        } finally {
            TestAuthentication.clear();
        }

        String body = "{\"dueDate\":\"2026-12-01\"}";
        String responseJson = mockMvc.perform(post("/risk/recommendations/" + recommendationId + "/accept")
                .header("Authorization", bearerFor(s.faculty().userId()))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interventionId").exists())
            .andReturn().getResponse().getContentAsString();

        UUID interventionId = UUID.fromString(responseJson.replaceAll(".*\"interventionId\":\"([0-9a-fA-F-]+)\".*", "$1"));
        mockMvc.perform(get("/risk/interventions/" + interventionId)
                .header("Authorization", bearerFor(s.faculty().userId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLANNED"));
    }
}
