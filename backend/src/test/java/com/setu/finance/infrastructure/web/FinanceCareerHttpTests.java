package com.setu.finance.infrastructure.web;

import com.setu.career.application.JobService;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 12 HTTP/controller tests — real Spring Security filter chain, real
 * PostgreSQL, exercising {@code PaymentController} and {@code
 * ApplicationController} end to end (the {@code Idempotency-Key} header,
 * the 409 conflict paths, and duplicate-application detection all the way
 * through {@code GlobalExceptionHandler}'s existing status mapping).
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceCareerHttpTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    JobService jobService;

    private String bearerFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId);
    }

    @Test
    void studentInitiatesPaymentOverHttp_withIdempotencyKey() throws Exception {
        var tenant = fixtures.provisionTenant("FHTTP1");
        UUID dept = fixtures.provisionDepartment(tenant, "FHTTP1D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "FHTTP1P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, "FHTTP1S");
        String key = "http-key-" + UUID.randomUUID();
        String body = "{\"studentId\":\"" + student.studentId() + "\",\"amount\":250,\"currency\":\"INR\",\"method\":\"UPI\"}";

        mockMvc.perform(post("/finance/payments")
                .header("Authorization", bearerFor(student.userId()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replayed").value(false));

        // Real HTTP retry with the SAME key and SAME body replays the original result instead of creating a second payment.
        mockMvc.perform(post("/finance/payments")
                .header("Authorization", bearerFor(student.userId()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void missingIdempotencyKeyHeader_returns400() throws Exception {
        var tenant = fixtures.provisionTenant("FHTTP2");
        UUID dept = fixtures.provisionDepartment(tenant, "FHTTP2D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "FHTTP2P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, "FHTTP2S");
        String body = "{\"studentId\":\"" + student.studentId() + "\",\"amount\":100,\"currency\":\"INR\",\"method\":\"UPI\"}";

        mockMvc.perform(post("/finance/payments")
                .header("Authorization", bearerFor(student.userId()))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void studentAppliesToJobOverHttp_duplicateReturns409() throws Exception {
        var tenant = fixtures.provisionTenant("FHTTP3");
        UUID dept = fixtures.provisionDepartment(tenant, "FHTTP3D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "FHTTP3P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, "FHTTP3S");
        UUID placementOfficerId = day12Fixtures.provisionPlacementOfficer(tenant, "FHTTP3PO");

        com.setu.identity.TestAuthentication.authenticateAs(placementOfficerId);
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(tenant.tenantId(), "Role", "Employer", null)).jobId();
        com.setu.identity.TestAuthentication.clear();

        String applyBody = "{\"jobId\":\"" + jobId + "\",\"studentId\":\"" + student.studentId() + "\"}";
        mockMvc.perform(post("/career/applications")
                .header("Authorization", bearerFor(student.userId()))
                .contentType(MediaType.APPLICATION_JSON).content(applyBody))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/career/applications")
                .header("Authorization", bearerFor(student.userId()))
                .contentType(MediaType.APPLICATION_JSON).content(applyBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("already applied to this job"));
    }

    @Test
    void unauthenticatedRequestToFinance_returns401() throws Exception {
        mockMvc.perform(get("/finance/accounts").param("tenantId", UUID.randomUUID().toString())
                .param("studentId", UUID.randomUUID().toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
