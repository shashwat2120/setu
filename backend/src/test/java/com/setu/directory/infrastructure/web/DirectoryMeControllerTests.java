package com.setu.directory.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /directory/me/profiles}: the caller's own student/faculty/guardian profile ids in one college. Served by the directory module
 * so that identity's {@code /identity/me} no longer depends on it (Sprint 6 coupling audit).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DirectoryMeControllerTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;

    private JsonNode profiles(UUID userId, UUID tenantId) throws Exception {
        String body = mockMvc.perform(get("/directory/me/profiles").param("tenantId", tenantId.toString())
                .header("Authorization", "Bearer " + jwtService.issueAccessToken(userId)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void aStudentGetsTheirOwnStudentIdAndNothingElse() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("DM1");

        JsonNode p = profiles(s.student().userId(), s.tenant().tenantId());

        assertThat(p.get("studentId").asText()).isEqualTo(s.student().studentId().toString());
        assertThat(p.get("facultyId").isNull()).isTrue();
        assertThat(p.get("guardianId").isNull()).isTrue();
    }

    @Test
    void aGuardianGetsTheirGuardianId() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("DM2");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "DM2G");

        JsonNode p = profiles(guardian.guardianUserId(), s.tenant().tenantId());

        assertThat(p.get("guardianId").asText()).isEqualTo(guardian.guardianId().toString());
        assertThat(p.get("studentId").isNull()).isTrue();
    }

    @Test
    void anotherPersonsCollegeIsRefusedNotAnsweredWithTheirData() throws Exception {
        var mine = day13Fixtures.provisionEnrolledScenario("DM3");
        var theirs = day13Fixtures.provisionEnrolledScenario("DM3X");

        mockMvc.perform(get("/directory/me/profiles").param("tenantId", theirs.tenant().tenantId().toString())
                .header("Authorization", "Bearer " + jwtService.issueAccessToken(mine.student().userId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorWithNoProfileGetsAllNulls() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("DM4");

        JsonNode p = profiles(s.tenant().adminUserId(), s.tenant().tenantId());

        assertThat(p.get("studentId").isNull()).isTrue();
        assertThat(p.get("facultyId").isNull()).isTrue();
        assertThat(p.get("guardianId").isNull()).isTrue();
    }

    @Test
    void anonymousCallersAreRefused() throws Exception {
        mockMvc.perform(get("/directory/me/profiles").param("tenantId", UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
    }

    @Test
    void identityMeNowCarriesOnlyTheAccountAndRoles() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("DM5");

        String body = mockMvc.perform(get("/identity/me").header("Authorization", "Bearer " + jwtService.issueAccessToken(s.student().userId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode me = objectMapper.readTree(body);

        assertThat(me.get("userId").asText()).isEqualTo(s.student().userId().toString());
        assertThat(me.get("roles")).isNotEmpty();
        assertThat(me.has("studentId")).as("profile ids are the directory's data now").isFalse();
        assertThat(me.has("facultyId")).isFalse();
        assertThat(me.has("guardianId")).isFalse();
    }
}
