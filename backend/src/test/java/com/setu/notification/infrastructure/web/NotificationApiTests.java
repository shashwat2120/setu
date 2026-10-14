package com.setu.notification.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.notification.application.NotificationEventProcessor;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The notifications API: a person sees and changes only their own notifications, and an id that is not theirs behaves exactly like an
 * id that does not exist (IDOR). Notifications are created through the real consumer entry point, {@link NotificationEventProcessor}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    NotificationEventProcessor processor;
    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;

    private record Person(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student) {
        String bearer(JwtService jwt) {
            return "Bearer " + jwt.issueAccessToken(student.userId());
        }
    }

    private Person person(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        return new Person(tenant, day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S"));
    }

    private UUID notify(Person p, String eventType) {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"tenantId\":\"" + p.tenant.tenantId() + "\",\"studentId\":\"" + p.student.studentId() + "\"}";
        processor.process(eventType, eventId.toString(), null, payload);
        return jdbc.sql("SELECT id FROM notification.notifications WHERE user_id = :u AND payload_json ->> 'eventId' = :e")
            .param("u", p.student.userId()).param("e", eventId.toString()).query(UUID.class).single();
    }

    private JsonNode list(Person p, String query) throws Exception {
        String body = mockMvc.perform(get("/notifications" + query).header("Authorization", p.bearer(jwtService)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void anonymousRequestsAreRefused() throws Exception {
        mockMvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/notifications/read-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/notifications/" + UUID.randomUUID() + "/read")).andExpect(status().isUnauthorized());
    }

    @Test
    void aPersonSeesOnlyTheirOwnNotificationsNewestFirst() throws Exception {
        var a = person("NA1");
        var b = person("NA1B");
        UUID first = notify(a, "StudentAdmitted");
        UUID second = notify(a, "RecommendationCreated");
        notify(b, "StudentAdmitted");

        JsonNode page = list(a, "");

        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(page.get("items").get(1).get("id").asText()).isEqualTo(first.toString());
        assertThat(page.get("items").get(0).get("title").asText()).isEqualTo("New study recommendation");
        assertThat(page.get("items").get(0).get("link").asText()).isEqualTo("/app/student/success");
        assertThat(page.get("items").get(0).get("readAt").isNull()).isTrue();
        assertThat(page.get("unreadCount").asLong()).isEqualTo(2);
    }

    @Test
    void markingOneReadUpdatesTheCountAndIsRepeatable() throws Exception {
        var a = person("NA2");
        UUID id = notify(a, "StudentAdmitted");
        notify(a, "RecommendationCreated");

        mockMvc.perform(post("/notifications/" + id + "/read").header("Authorization", a.bearer(jwtService))).andExpect(status().isOk());
        String firstReadAt = jdbc.sql("SELECT read_at::text FROM notification.notifications WHERE id = :id").param("id", id).query(String.class).single();
        mockMvc.perform(post("/notifications/" + id + "/read").header("Authorization", a.bearer(jwtService))).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT read_at::text FROM notification.notifications WHERE id = :id").param("id", id).query(String.class).single())
            .as("reading again does not move the read time").isEqualTo(firstReadAt);
        assertThat(list(a, "").get("unreadCount").asLong()).isEqualTo(1);
        assertThat(list(a, "?unreadOnly=true").get("items")).hasSize(1);
    }

    @Test
    void anotherPersonsNotificationIsNotFoundAndStaysUnread() throws Exception {
        var a = person("NA3");
        var b = person("NA3B");
        UUID aId = notify(a, "StudentAdmitted");

        mockMvc.perform(post("/notifications/" + aId + "/read").header("Authorization", b.bearer(jwtService))).andExpect(status().isNotFound());
        mockMvc.perform(post("/notifications/" + UUID.randomUUID() + "/read").header("Authorization", b.bearer(jwtService))).andExpect(status().isNotFound());

        assertThat(jdbc.sql("SELECT read_at FROM notification.notifications WHERE id = :id").param("id", aId).query(java.sql.Timestamp.class).optional())
            .as("still unread").isEmpty();
    }

    @Test
    void readAllChangesOnlyTheCallersNotifications() throws Exception {
        var a = person("NA4");
        var b = person("NA4B");
        notify(a, "StudentAdmitted");
        notify(a, "RecommendationCreated");
        notify(b, "StudentAdmitted");

        String body = mockMvc.perform(post("/notifications/read-all").header("Authorization", a.bearer(jwtService)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("updated").asInt()).isEqualTo(2);
        assertThat(list(a, "").get("unreadCount").asLong()).isZero();
        assertThat(list(b, "").get("unreadCount").asLong()).isEqualTo(1);
    }

    @Test
    void aRowFromAnotherCollegeIsNotShownEvenIfItNamesThisUser() throws Exception {
        var a = person("NA5");
        var other = fixtures.provisionTenant("NA5X");
        jdbc.sql("INSERT INTO notification.notifications (tenant_id, user_id, type, payload_json) VALUES (:t, :u, 'GRADE_PUBLISHED', CAST(:p AS jsonb))")
            .param("t", other.tenantId()).param("u", a.student.userId()).param("p", "{\"title\":\"Other college\",\"message\":\"x\"}").update();

        JsonNode page = list(a, "");

        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("unreadCount").asLong()).isZero();
    }

    @Test
    void onlyInAppPathsAreEverReturnedAsLinks() throws Exception {
        var a = person("NA6");
        for (String link : new String[] {"https://evil.example/phish", "//evil.example", "javascript:alert(1)", "/app/student/grades"}) {
            jdbc.sql("INSERT INTO notification.notifications (tenant_id, user_id, type, payload_json) VALUES (:t, :u, 'GRADE_PUBLISHED', CAST(:p AS jsonb))")
                .param("t", a.tenant.tenantId()).param("u", a.student.userId())
                .param("p", "{\"title\":\"t\",\"message\":\"m\",\"link\":\"" + link + "\"}").update();
        }

        JsonNode items = list(a, "").get("items");

        int safe = 0;
        for (JsonNode item : items) {
            if (!item.get("link").isNull()) {
                assertThat(item.get("link").asText()).isEqualTo("/app/student/grades");
                safe++;
            }
        }
        assertThat(items).hasSize(4);
        assertThat(safe).isEqualTo(1);
    }

    @Test
    void theLimitIsBoundedButTheUnreadCountCoversEverything() throws Exception {
        var a = person("NA7");
        for (int i = 0; i < 3; i++) {
            notify(a, "RecommendationCreated");
        }

        JsonNode page = list(a, "?limit=2");
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("unreadCount").asLong()).isEqualTo(3);

        assertThat(list(a, "?limit=0").get("items")).as("a limit below 1 is raised to 1").hasSize(1);
        assertThat(list(a, "?limit=100000").get("items")).hasSize(3);
    }
}
