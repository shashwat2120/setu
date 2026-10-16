package com.setu.notification;

import com.setu.notification.application.NotificationEventProcessor;
import com.setu.notification.application.PoisonEventException;
import com.setu.notification.application.RecipientNotKnownException;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.AuthorizationEngine;
import com.setu.shared.readmodel.ReadModelAcademicDirectory;
import com.setu.shared.security.Jwks;
import com.setu.shared.security.JwksKeySource;
import com.setu.shared.websocket.LiveBroadcastRequest;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Notification/Realtime service on its own: only the notification module and the shared kernel are in the context; the two things it must
 * ask Academic (who owns a student profile, whose enrollment a grade belongs to) are answered from its own copy ({@code notification.rm_*});
 * requests are authorized from that copy and a token verified against Identity's published keys; and a stored notification is announced on its
 * recipient's own live topic, after it is stored. The Kafka consumers are off here (their policies are tested on their own); the processor is
 * given events directly, as the listener would.
 */
@SpringBootTest(classes = NotificationServiceApplication.class, properties = {
    "setu.service=notification", "setu.auth.mode=remote", "app.readmodel.schema=notification", "app.readmodel.consumer.enabled=false",
    "app.notification.consumer.enabled=false", "app.realtime.enabled=false"})
@AutoConfigureMockMvc
@Import({NotificationServiceWiringTests.IdentityKeys.class, NotificationServiceWiringTests.LiveRecorder.class})
class NotificationServiceWiringTests {

    private static final KeyPair IDENTITY_KEY = newPair();

    @TestConfiguration
    static class IdentityKeys {
        @Bean
        @Primary
        JwksKeySource testJwksKeySource() {
            String jwks = Jwks.toJson(Map.of("test-key", IDENTITY_KEY.getPublic()));
            return new JwksKeySource(() -> jwks, Clock.systemUTC(), Duration.ofMinutes(10), Duration.ofSeconds(30));
        }
    }

    /** Records what the processor announces for live delivery (the transport itself is off in this test). */
    @TestConfiguration
    static class LiveRecorder {
        static final List<LiveBroadcastRequest> SEEN = new CopyOnWriteArrayList<>();

        @EventListener
        void on(LiveBroadcastRequest request) {
            SEEN.add(request);
        }
    }

    private static final List<String> OTHER_MODULES = List.of("identity", "institution", "catalog", "enrollment", "directory", "guardianaccess",
        "learning", "assessment", "attendance", "record", "finance", "career", "risk", "aiexplain", "analytics");

    @Autowired
    ApplicationContext context;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    NotificationEventProcessor processor;

    private UUID tenant;
    private UUID student;
    private UUID studentUser;
    private UUID otherUser;
    private UUID enrollment;

    private static KeyPair newPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void seedReadModel() {
        LiveRecorder.SEEN.clear();
        tenant = UUID.randomUUID();
        student = UUID.randomUUID();
        studentUser = UUID.randomUUID();
        otherUser = UUID.randomUUID();
        enrollment = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification.rm_students (student_id, tenant_id, owner_user_id, updated_at) VALUES (:s, :t, :u, now())")
            .param("s", student).param("t", tenant).param("u", studentUser).update();
        jdbc.sql("INSERT INTO notification.rm_enrollments (enrollment_id, tenant_id, section_id, student_id, active, updated_at) VALUES (:e, :t, :sec, :st, true, now())")
            .param("e", enrollment).param("t", tenant).param("sec", UUID.randomUUID()).param("st", student).update();
        for (UUID user : List.of(studentUser, otherUser)) {
            jdbc.sql("INSERT INTO notification.rm_role_grants (user_role_id, user_id, tenant_id, role_code, starts_at) VALUES (:id, :u, :t, 'STUDENT', now() - interval '1 minute')")
                .param("id", UUID.randomUUID()).param("u", user).param("t", tenant).update();
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM notification.notifications WHERE tenant_id = :t").param("t", tenant).update();
        for (String table : List.of("rm_students", "rm_enrollments", "rm_role_grants")) {
            jdbc.sql("DELETE FROM notification." + table + " WHERE tenant_id = :t").param("t", tenant).update();
        }
    }

    private String token(UUID user) {
        return Jwts.builder().header().keyId("test-key").and()
            .subject(user.toString()).issuer("setu").audience().add("setu-api").and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).id(UUID.randomUUID().toString())
            .signWith(IDENTITY_KEY.getPrivate(), Jwts.SIG.ES256).compact();
    }

    private static String json(Object... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            sb.append(i > 0 ? "," : "").append('"').append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    @Test
    void onlyTheNotificationModuleAndTheKernelAreInTheContext() {
        List<String> foreign = Arrays.stream(context.getBeanDefinitionNames())
            .map(context::getType)
            .filter(type -> type != null && type.getName().startsWith("com.setu."))
            .map(Class::getName)
            .filter(name -> OTHER_MODULES.stream().anyMatch(module -> name.startsWith("com.setu." + module + ".")))
            .toList();
        assertThat(foreign).isEmpty();
        assertThat(Arrays.stream(context.getBeanDefinitionNames()).map(context::getType).filter(t -> t != null).map(Class::getName))
            .noneMatch(n -> n.startsWith("com.setu.shared.admin.") || n.startsWith("com.setu.shared.seed."));
    }

    @Test
    void recipientsAndAuthorizationComeFromTheServicesOwnCopy() {
        assertThat(context.getBean(AcademicDirectory.class)).isExactlyInstanceOf(ReadModelAcademicDirectory.class);
        assertThat(context.getBean(AuthorizationContext.class)).isExactlyInstanceOf(AuthorizationEngine.class);
    }

    @Test
    void anAdmissionNotifiesTheOwnerFoundInTheCopyAndAnnouncesItOnTheirOwnTopicOnly() {
        UUID eventId = UUID.randomUUID();

        var outcome = processor.process("StudentAdmitted", eventId.toString(), tenant.toString(), json("eventId", eventId, "tenantId", tenant, "studentId", student));

        assertThat(outcome).isEqualTo(NotificationEventProcessor.Outcome.CREATED);
        assertThat(jdbc.sql("SELECT type FROM notification.notifications WHERE user_id = :u").param("u", studentUser).query(String.class).list())
            .containsExactly("ADMISSION_CONFIRMED");
        assertThat(LiveRecorder.SEEN).hasSize(1);
        assertThat(LiveRecorder.SEEN.get(0).destination()).isEqualTo("/topic/notifications/" + studentUser);
        assertThat(LiveRecorder.SEEN.get(0).payload().toString()).contains("ADMISSION_CONFIRMED").doesNotContain(otherUser.toString());
    }

    @Test
    void aGradeForAnEnrollmentInTheCopyReachesThatEnrollmentsStudent() {
        UUID eventId = UUID.randomUUID();

        processor.process("GradePublished", eventId.toString(), tenant.toString(), json("eventId", eventId, "tenantId", tenant, "enrollmentId", enrollment));

        assertThat(jdbc.sql("SELECT type FROM notification.notifications WHERE user_id = :u").param("u", studentUser).query(String.class).list())
            .containsExactly("GRADE_PUBLISHED");
    }

    @Test
    void aRedeliveredEventNeitherNotifiesNorAnnouncesTwice() {
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", tenant, "studentId", student);

        processor.process("StudentAdmitted", eventId.toString(), tenant.toString(), payload);
        var second = processor.process("StudentAdmitted", eventId.toString(), tenant.toString(), payload);

        assertThat(second).isEqualTo(NotificationEventProcessor.Outcome.DUPLICATE);
        assertThat(jdbc.sql("SELECT count(*) FROM notification.notifications WHERE user_id = :u").param("u", studentUser).query(Long.class).single()).isEqualTo(1L);
        assertThat(LiveRecorder.SEEN).hasSize(1);
    }

    @Test
    void aStudentTheCopyDoesNotKnowYetIsRetriedNotPoisonedAndLeavesNothingBehind() {
        UUID eventId = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process("StudentAdmitted", eventId.toString(), tenant.toString(), json("eventId", eventId, "tenantId", tenant, "studentId", unknown)))
            .isInstanceOf(RecipientNotKnownException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM notification.processed_events WHERE event_id = :e").param("e", eventId).query(Long.class).single())
            .as("not recorded as processed, so the retry (or a replay from the dead-letter topic) can still deliver it").isZero();
        assertThat(LiveRecorder.SEEN).isEmpty();
    }

    @Test
    void anEventFromAnotherCollegeThanTheStudentsIsPoison() {
        UUID eventId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process("StudentAdmitted", eventId.toString(), otherTenant.toString(),
            json("eventId", eventId, "tenantId", otherTenant, "studentId", student)))
            .isInstanceOf(PoisonEventException.class);
        assertThat(LiveRecorder.SEEN).isEmpty();
    }

    @Test
    void aUserSeesOnlyTheirOwnNotificationsAndNeedsAValidToken() throws Exception {
        UUID eventId = UUID.randomUUID();
        processor.process("StudentAdmitted", eventId.toString(), tenant.toString(), json("eventId", eventId, "tenantId", tenant, "studentId", student));

        assertThat(mockMvc.perform(get("/notifications")).andReturn().getResponse().getStatus()).as("no token").isEqualTo(401);
        assertThat(mockMvc.perform(get("/notifications").header("Authorization", "Bearer garbage")).andReturn().getResponse().getStatus()).isEqualTo(401);

        String mine = mockMvc.perform(get("/notifications").header("Authorization", "Bearer " + token(studentUser))).andReturn().getResponse().getContentAsString();
        assertThat(mine).contains("ADMISSION_CONFIRMED").contains("\"unreadCount\":1");

        String theirs = mockMvc.perform(get("/notifications").header("Authorization", "Bearer " + token(otherUser))).andReturn().getResponse().getContentAsString();
        assertThat(theirs).doesNotContain("ADMISSION_CONFIRMED").contains("\"unreadCount\":0");

        UUID notificationId = jdbc.sql("SELECT id FROM notification.notifications WHERE user_id = :u").param("u", studentUser).query(UUID.class).single();
        assertThat(mockMvc.perform(post("/notifications/" + notificationId + "/read").header("Authorization", "Bearer " + token(otherUser))).andReturn().getResponse().getStatus())
            .as("someone else's notification cannot be marked read").isEqualTo(404);
    }
}
