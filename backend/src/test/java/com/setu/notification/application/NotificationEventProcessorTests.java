package com.setu.notification.application;

import com.setu.shared.websocket.LiveBroadcastRequest;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The notification consumer's business rules, independent of Kafka: one notification per event id (idempotency, canonical spec §8),
 * recipients derived server-side, tenant mismatches and unresolvable events refused as poison, and the event id and the notification
 * committing together. Deliberately not {@code @Transactional}: the processor owns its own transaction and the concurrency test needs
 * real commits.
 */
@SpringBootTest
@Import(NotificationEventProcessorTests.FailingAnnouncement.class)
class NotificationEventProcessorTests {

    /**
     * Lets a test make the step after the notification insert fail. The notification's tenant and user were once foreign keys, and a
     * bad user id was how this test made the write fail; those constraints went when the service was extracted (V86), the invariant did not.
     */
    @TestConfiguration
    static class FailingAnnouncement {
        static volatile boolean failNext;

        @EventListener
        void on(LiveBroadcastRequest request) {
            if (failNext) {
                throw new IllegalStateException("simulated failure after the notification was written");
            }
        }
    }

    @Autowired
    NotificationEventProcessor processor;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;

    private record Scenario(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student, UUID enrollmentId) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, sectionId);
        keepLiveConsumerOut(tenant.tenantId());
        return new Scenario(tenant, student, enrollmentId);
    }

    /**
     * The development database is shared with a running backend whose own notification consumer would otherwise also act on the events these
     * fixtures write (a student's StudentAdmitted) and add notifications while a test is counting them. Mark the fixture's events as already
     * handled, and clear anything it got in first, so a test only ever sees the notifications it makes itself. (A clean CI run has no such
     * consumer; this is then a harmless no-op.)
     */
    private void keepLiveConsumerOut(UUID tenantId) {
        jdbc.sql("INSERT INTO notification.processed_events (event_id, event_type) SELECT id, event_type FROM governance.outbox_events WHERE tenant_id = :t ON CONFLICT (event_id) DO NOTHING")
            .param("t", tenantId).update();
        jdbc.sql("DELETE FROM notification.notifications WHERE tenant_id = :t").param("t", tenantId).update();
    }


    private static String json(Object... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            Object v = pairs[i + 1];
            sb.append('"').append(pairs[i]).append("\":").append(v instanceof Number ? v.toString() : "\"" + v + "\"");
        }
        return sb.append('}').toString();
    }

    private List<String> notificationTypes(UUID userId) {
        return jdbc.sql("SELECT type FROM notification.notifications WHERE user_id = :u ORDER BY created_at").param("u", userId).query(String.class).list();
    }

    private int processedRows(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM notification.processed_events WHERE event_id = :e").param("e", eventId).query(Integer.class).single();
    }

    @Test
    void anAdmissionNotifiesTheStudentsOwnUserOnce() {
        var s = setUp("NP1");
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", s.tenant.tenantId(), "studentId", s.student.studentId());

        assertThat(processor.process("StudentAdmitted", eventId.toString(), s.tenant.tenantId().toString(), payload))
            .isEqualTo(NotificationEventProcessor.Outcome.CREATED);

        assertThat(notificationTypes(s.student.userId())).containsExactly("ADMISSION_CONFIRMED");
        assertThat(processedRows(eventId)).isEqualTo(1);
    }

    @Test
    void aRedeliveredEventDoesNotNotifyTwice() {
        var s = setUp("NP2");
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", s.tenant.tenantId(), "studentId", s.student.studentId());

        processor.process("RecommendationCreated", eventId.toString(), null, payload);
        var second = processor.process("RecommendationCreated", eventId.toString(), null, payload);

        assertThat(second).isEqualTo(NotificationEventProcessor.Outcome.DUPLICATE);
        assertThat(notificationTypes(s.student.userId())).containsExactly("RECOMMENDATION_AVAILABLE");
    }

    @Test
    void parallelDeliveriesOfOneEventProduceExactlyOneNotification() throws Exception {
        var s = setUp("NP3");
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", s.tenant.tenantId(), "studentId", s.student.studentId());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<NotificationEventProcessor.Outcome>> calls = IntStream.range(0, 8)
                .<Callable<NotificationEventProcessor.Outcome>>mapToObj(i -> () -> processor.process("PaymentFailed", eventId.toString(), null, payload))
                .toList();
            List<NotificationEventProcessor.Outcome> outcomes = new ArrayList<>();
            for (Future<NotificationEventProcessor.Outcome> f : pool.invokeAll(calls)) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).filteredOn(o -> o == NotificationEventProcessor.Outcome.CREATED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == NotificationEventProcessor.Outcome.DUPLICATE).hasSize(7);
            assertThat(notificationTypes(s.student.userId())).containsExactly("PAYMENT_FAILED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aPublishedGradeReachesTheStudentOwningTheEnrollment() {
        var s = setUp("NP4");
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", s.tenant.tenantId(), "enrollmentId", s.enrollmentId, "gradeId", UUID.randomUUID());

        processor.process("GradePublished", eventId.toString(), s.tenant.tenantId().toString(), payload);

        assertThat(notificationTypes(s.student.userId())).containsExactly("GRADE_PUBLISHED");
    }

    @Test
    void paymentMessagesCarryTheAmountAndNothingElseAboutThePayment() {
        var s = setUp("NP5");
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"tenantId\":\"" + s.tenant.tenantId() + "\",\"studentId\":\"" + s.student.studentId()
            + "\",\"paymentId\":\"" + UUID.randomUUID() + "\",\"amount\":3000.00,\"currency\":\"INR\"}";

        processor.process("PaymentSuccessful", eventId.toString(), null, payload);

        String message = jdbc.sql("SELECT payload_json ->> 'message' FROM notification.notifications WHERE user_id = :u")
            .param("u", s.student.userId()).query(String.class).single();
        assertThat(message).isEqualTo("Your payment of INR 3000.00 was received.");
    }

    @Test
    void aWaitlistedStudentAndAPromotedStudentAreEachTold() {
        var s = setUp("NP12");
        UUID waitlistedEvent = UUID.randomUUID();
        UUID promotedEvent = UUID.randomUUID();

        processor.process("StudentWaitlisted", waitlistedEvent.toString(), null,
            "{\"eventId\":\"" + waitlistedEvent + "\",\"tenantId\":\"" + s.tenant.tenantId() + "\",\"studentId\":\"" + s.student.studentId() + "\",\"position\":3}");
        processor.process("WaitlistConverted", promotedEvent.toString(), null, json("eventId", promotedEvent, "tenantId", s.tenant.tenantId(),
            "studentId", s.student.studentId(), "sectionId", UUID.randomUUID()));

        assertThat(notificationTypes(s.student.userId())).containsExactly("WAITLISTED", "WAITLIST_PROMOTED");
        String message = jdbc.sql("SELECT payload_json ->> 'message' FROM notification.notifications WHERE user_id = :u AND type = 'WAITLISTED'")
            .param("u", s.student.userId()).query(String.class).single();
        assertThat(message).contains("number 3");
    }

    @Test
    void enrollmentEventsThatNeedNoNotificationAreIgnored() {
        var s = setUp("NP13");
        UUID eventId = UUID.randomUUID();

        var outcome = processor.process("StudentEnrolled", eventId.toString(), null,
            json("eventId", eventId, "tenantId", s.tenant.tenantId(), "studentId", s.student.studentId(), "enrollmentId", s.enrollmentId));

        assertThat(outcome).isEqualTo(NotificationEventProcessor.Outcome.IGNORED);
        assertThat(notificationTypes(s.student.userId())).isEmpty();
    }

    @Test
    void anInterventionIsAddressedToItsOwnerNotToTheStudent() {
        var s = setUp("NP6");
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", s.tenant.tenantId(), "studentId", s.student.studentId(),
            "ownerUserId", s.tenant.adminUserId(), "interventionId", UUID.randomUUID());

        processor.process("InterventionCreated", eventId.toString(), null, payload);

        assertThat(notificationTypes(s.tenant.adminUserId())).containsExactly("INTERVENTION_ASSIGNED");
        assertThat(notificationTypes(s.student.userId())).isEmpty();
    }

    @Test
    void anEventTypeWithoutANotificationIsIgnoredAndNotRecorded() {
        var s = setUp("NP7");
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", s.tenant.tenantId(), "studentId", s.student.studentId());

        assertThat(processor.process("StudentSuspended", eventId.toString(), null, payload)).isEqualTo(NotificationEventProcessor.Outcome.IGNORED);

        assertThat(notificationTypes(s.student.userId())).isEmpty();
        assertThat(processedRows(eventId)).isZero();
    }

    @Test
    void anEventWhoseTenantDoesNotMatchTheStudentIsPoisonAndDeliversNothing() {
        var s = setUp("NP8");
        var other = fixtures.provisionTenant("NP8X");
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", other.tenantId(), "studentId", s.student.studentId());

        assertThatThrownBy(() -> processor.process("StudentAdmitted", eventId.toString(), null, payload))
            .isInstanceOf(PoisonEventException.class);

        assertThat(notificationTypes(s.student.userId())).isEmpty();
        assertThat(processedRows(eventId)).isZero();
    }

    @Test
    void aTenantHeaderThatContradictsThePayloadIsPoison() {
        var s = setUp("NP9");
        var other = fixtures.provisionTenant("NP9X");
        UUID eventId = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", s.tenant.tenantId(), "studentId", s.student.studentId());

        assertThatThrownBy(() -> processor.process("StudentAdmitted", eventId.toString(), other.tenantId().toString(), payload))
            .isInstanceOf(PoisonEventException.class);
        assertThat(notificationTypes(s.student.userId())).isEmpty();
    }

    @Test
    void anUnknownStudentOrEnrollmentIsRetriedThenDeadLetteredNotDelivered() {
        var tenant = fixtures.provisionTenant("NP10");
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process("StudentAdmitted", eventId.toString(), null,
            json("eventId", eventId, "tenantId", tenant.tenantId(), "studentId", UUID.randomUUID())))
            .isInstanceOf(RecipientNotKnownException.class);
        assertThatThrownBy(() -> processor.process("GradePublished", eventId.toString(), null,
            json("eventId", eventId, "tenantId", tenant.tenantId(), "enrollmentId", UUID.randomUUID())))
            .isInstanceOf(RecipientNotKnownException.class);
    }

    @Test
    void malformedPayloadsArePoison() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        assertThatThrownBy(() -> processor.process("StudentAdmitted", eventId.toString(), null, "not json at all"))
            .isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> processor.process("StudentAdmitted", eventId.toString(), null, "[1,2]"))
            .isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> processor.process("StudentAdmitted", eventId.toString(), null, json("tenantId", tenantId)))
            .as("no studentId").isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> processor.process("StudentAdmitted", "not-a-uuid", null, json("tenantId", tenantId, "studentId", UUID.randomUUID())))
            .as("bad event id").isInstanceOf(PoisonEventException.class);
    }

    @Test
    void theEventIdIsNotLeftBehindWhenTheNotificationCannotBeWritten() {
        // The processed-event row is written first and the notification second. If anything after the first fails, the first must roll back
        // with it, otherwise the retry would be skipped as a "duplicate" and the notification lost.
        var s = setUp("NP11");
        UUID eventId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String payload = json("eventId", eventId, "tenantId", s.tenant.tenantId(), "studentId", s.student.studentId(),
            "ownerUserId", owner, "interventionId", UUID.randomUUID());

        FailingAnnouncement.failNext = true;
        try {
            assertThatThrownBy(() -> processor.process("InterventionCreated", eventId.toString(), null, payload))
                .isInstanceOf(IllegalStateException.class);
        } finally {
            FailingAnnouncement.failNext = false;
        }

        assertThat(processedRows(eventId)).as("the event id is not left behind").isZero();
        assertThat(notificationTypes(owner)).as("nor is the notification").isEmpty();
        assertThat(processor.process("InterventionCreated", eventId.toString(), null, payload))
            .as("the retry delivers it").isEqualTo(NotificationEventProcessor.Outcome.CREATED);
        assertThat(notificationTypes(owner)).containsExactly("INTERVENTION_ASSIGNED");
    }
}
