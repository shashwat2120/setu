package com.setu.aifacts.infrastructure.web;

import com.setu.identity.infrastructure.security.JwtService;
import com.setu.testsupport.Day13TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The facts as an API, which is what the AI service calls with the user's own token. The point of all of it: whoever asks gets what they
 * could read anyway and no more, a refusal is an ordinary answer for the model to report, and the one thing the advisor may write (its audit
 * record) cannot be used to write anything else or to write as someone else.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiFactsControllerTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;

    private String bearerFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId);
    }

    @Test
    void everyFactNeedsAValidTokenAndTheAuditToo() throws Exception {
        for (String path : new String[] {"/ai-facts/me", "/ai-facts/attendance", "/ai-facts/academic-progress", "/ai-facts/assessment-results",
            "/ai-facts/deadlines", "/ai-facts/learning-resources", "/ai-facts/transcript", "/ai-facts/fee-status",
            "/ai-facts/policy-search?query=x", "/ai-facts/skill-gap?jobId=" + UUID.randomUUID()}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", "Bearer not.a.token")).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/ai-facts/audit").contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"AI_CHAT_COMPLETED\",\"correlationId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void theCallerIsWhoTheTokenSaysAndTheCollegeTheyActIn() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("AIF1");

        mockMvc.perform(get("/ai-facts/me").header("Authorization", bearerFor(s.student().userId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(s.student().userId().toString()))
            .andExpect(jsonPath("$.tenantId").value(s.tenant().tenantId().toString()));
    }

    @Test
    void aStudentGetsTheirOwnDataByDefaultAndARefusalNotAnErrorForSomeoneElses() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("AIF2");
        day13Fixtures.recordAttendanceSession(s, true);
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant(), s.programId(), s.curriculumId(), "AIF2O");
        day9Fixtures.enrollStudent(other, s.sectionId());
        String me = bearerFor(s.student().userId());

        mockMvc.perform(get("/ai-facts/attendance").header("Authorization", me))
            .andExpect(status().isOk()).andExpect(jsonPath("$.percentage").value(100.0));
        mockMvc.perform(get("/ai-facts/attendance").param("studentId", s.student().studentId().toString()).header("Authorization", me))
            .andExpect(status().isOk()).andExpect(jsonPath("$.percentage").value(100.0));

        mockMvc.perform(get("/ai-facts/attendance").param("studentId", other.studentId().toString()).header("Authorization", me))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reason").value("not authorized to view this student's attendance"))
            .andExpect(jsonPath("$.percentage").doesNotExist());
        mockMvc.perform(get("/ai-facts/transcript").param("studentId", other.studentId().toString()).header("Authorization", me))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reason").exists());
        mockMvc.perform(get("/ai-facts/attendance").param("studentId", "not-a-uuid").header("Authorization", me))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("student not found"));
    }

    @Test
    void theAuditRecordIsWrittenAsTheTokenHolderNotAsWhoeverTheBodyNames() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("AIF3");
        UUID correlation = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();

        mockMvc.perform(post("/ai-facts/audit").header("Authorization", bearerFor(s.student().userId())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"AI_CHAT_COMPLETED\",\"correlationId\":\"" + correlation + "\",\"userId\":\"" + someoneElse
                    + "\",\"tenantId\":\"" + UUID.randomUUID() + "\",\"details\":{\"model\":\"m\"}}"))
            .andExpect(status().isNoContent());

        var row = jdbc.sql("SELECT actor_user_id, tenant_id, action, resource_type FROM governance.audit_logs WHERE resource_id = :c")
            .param("c", correlation).query().singleRow();
        assertThat(row.get("actor_user_id")).isEqualTo(s.student().userId());
        assertThat(row.get("tenant_id")).isEqualTo(s.tenant().tenantId());
        assertThat(row.get("action")).isEqualTo("AI_CHAT_COMPLETED");
        assertThat(row.get("resource_type")).isEqualTo("ai_chat");
    }

    @Test
    void theAuditEndpointCannotBeUsedToWriteAnythingButTheAdvisorsOwnRecords() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("AIF4");
        String me = bearerFor(s.student().userId());
        UUID correlation = UUID.randomUUID();
        String tooLong = "x".repeat(AiFactsController.MAX_AUDIT_VALUE_LENGTH + 1);

        for (String body : new String[] {
            "{\"action\":\"LOGIN_SUCCEEDED\",\"correlationId\":\"" + correlation + "\"}",
            "{\"action\":\"GRADE_PUBLISHED\",\"correlationId\":\"" + correlation + "\"}",
            "{\"action\":\"\",\"correlationId\":\"" + correlation + "\"}",
            "{\"correlationId\":\"" + correlation + "\"}",
            "{\"action\":\"AI_CHAT_COMPLETED\"}",
            "{\"action\":\"AI_CHAT_COMPLETED\",\"correlationId\":\"" + correlation + "\",\"details\":{\"a\":\"" + tooLong + "\"}}",
            "{\"action\":\"AI_CHAT_COMPLETED\",\"correlationId\":\"" + correlation + "\",\"details\":{" + manyDetails() + "}}"}) {
            mockMvc.perform(post("/ai-facts/audit").header("Authorization", me).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM governance.audit_logs WHERE resource_id = :c").param("c", correlation).query(Long.class).single())
            .as("nothing was written").isZero();
    }

    private static String manyDetails() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= AiFactsController.MAX_AUDIT_DETAILS; i++) {
            sb.append(i > 0 ? "," : "").append("\"k").append(i).append("\":\"v\"");
        }
        return sb.toString();
    }
}
