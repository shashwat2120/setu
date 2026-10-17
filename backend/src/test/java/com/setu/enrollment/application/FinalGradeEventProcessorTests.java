package com.setu.enrollment.application;

import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publishing a final grade completes the enrollment, independent of Kafka: the processor is handed the payload the Assessment service writes
 * ({@code FinalGradePublished}) the way the listener would. Deliberately not {@code @Transactional}: the processor owns its transaction, and the
 * concurrency test needs real commits.
 * <p>
 * Covers: the first final grade completes an enrollment and records the grade as its final status, who did it and the event; a correction changes
 * the final status and keeps the enrollment completed; a redelivered event, or the same grade again, changes nothing; enrollments a final grade does
 * not apply to are left alone; every refusal leaves nothing behind, including the processed-event record; and events racing for one enrollment
 * are serialised.
 */
@SpringBootTest
class FinalGradeEventProcessorTests {

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    FinalGradeEventProcessor processor;
    @Autowired
    JdbcClient jdbc;

    /**
     * The development database is shared with a running backend whose own enrollment consumer would otherwise also act on the events these
     * fixtures write. Mark the fixture's events as already handled, so a test only ever sees the changes it asks for. (A clean CI run has no
     * such consumer; this is then a harmless no-op.)
     */
    private Day13TestFixtures.Scenario scenario(String prefix) {
        var s = fixtures.provisionEnrolledScenario(prefix);
        jdbc.sql("INSERT INTO enrollment.processed_events (event_id, event_type) SELECT id, event_type FROM governance.outbox_events WHERE tenant_id = :t ON CONFLICT (event_id) DO NOTHING")
            .param("t", s.tenant().tenantId()).update();
        return s;
    }

    private static String payload(UUID eventId, UUID tenantId, UUID enrollmentId, String gradeCode, UUID publishedBy) {
        return "{\"eventId\":\"" + eventId + "\",\"tenantId\":\"" + tenantId + "\",\"enrollmentId\":\"" + enrollmentId + "\",\"gradeId\":\""
            + UUID.randomUUID() + "\",\"componentId\":\"" + UUID.randomUUID() + "\",\"version\":1,\"gradeCode\":\"" + gradeCode
            + "\",\"gradePoint\":3.0,\"publishedBy\":\"" + publishedBy + "\"}";
    }

    private FinalGradeEventProcessor.Outcome publish(Day13TestFixtures.Scenario s, String gradeCode) {
        UUID eventId = UUID.randomUUID();
        return processor.process("FinalGradePublished", eventId.toString(), s.tenant().tenantId().toString(),
            payload(eventId, s.tenant().tenantId(), s.enrollmentId(), gradeCode, s.faculty().userId()));
    }

    private String status(UUID enrollmentId) {
        return jdbc.sql("SELECT status FROM enrollment.enrollments WHERE id = :id").param("id", enrollmentId).query(String.class).single();
    }

    private String finalStatus(UUID enrollmentId) {
        return jdbc.sql("SELECT final_status FROM enrollment.enrollments WHERE id = :id").param("id", enrollmentId).query(String.class).optional().orElse(null);
    }

    private int actions(UUID enrollmentId, String action) {
        return jdbc.sql("SELECT count(*) FROM enrollment.enrollment_actions WHERE enrollment_id = :id AND action = :a")
            .param("id", enrollmentId).param("a", action).query(Integer.class).single();
    }

    private List<String> completedEvents(UUID enrollmentId) {
        return jdbc.sql("SELECT payload_json::text FROM governance.outbox_events WHERE event_type = 'EnrollmentCompleted' AND aggregate_id = :id ORDER BY occurred_at")
            .param("id", enrollmentId).query(String.class).list();
    }

    private int processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM enrollment.processed_events WHERE event_id = :e").param("e", eventId).query(Integer.class).single();
    }

    @Test
    void theFirstFinalGradeCompletesTheEnrollmentAndIsRecordedAsItsFinalStatus() {
        var s = scenario("FG1");
        assertThat(status(s.enrollmentId())).isEqualTo("ENROLLED");

        var outcome = publish(s, "b+");

        assertThat(outcome).isEqualTo(FinalGradeEventProcessor.Outcome.COMPLETED);
        assertThat(status(s.enrollmentId())).isEqualTo("COMPLETED");
        assertThat(finalStatus(s.enrollmentId())).as("stored as published, normalised").isEqualTo("B+");
    }

    @Test
    void whoDidItIsOnRecordAndTheEventSaysWhatHappened() {
        var s = scenario("FG2");

        publish(s, "A");

        var action = jdbc.sql("SELECT actor_user_id, metadata_json::text AS metadata FROM enrollment.enrollment_actions WHERE enrollment_id = :id AND action = 'ENROLLMENT_COMPLETED'")
            .param("id", s.enrollmentId()).query().singleRow();
        assertThat(action.get("actor_user_id")).as("the faculty member who published the grade").isEqualTo(s.faculty().userId());
        assertThat((String) action.get("metadata")).contains("\"finalGrade\"").contains("A");
        List<String> events = completedEvents(s.enrollmentId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).contains("\"finalGrade\": \"A\"").contains("\"passed\": true").contains("\"corrected\": false")
            .contains(s.enrollmentId().toString());
    }

    @Test
    void aFailingGradeCompletesTheEnrollmentToo() {
        var s = scenario("FG3");

        publish(s, "F");

        assertThat(status(s.enrollmentId())).as("the course is over; the student did not pass it").isEqualTo("COMPLETED");
        assertThat(finalStatus(s.enrollmentId())).isEqualTo("F");
        assertThat(completedEvents(s.enrollmentId()).get(0)).contains("\"passed\": false");
    }

    @Test
    void aCorrectionChangesTheFinalStatusAndTheEnrollmentStaysCompleted() {
        var s = scenario("FG4");
        publish(s, "F");

        var outcome = publish(s, "C");

        assertThat(outcome).isEqualTo(FinalGradeEventProcessor.Outcome.CORRECTED);
        assertThat(status(s.enrollmentId())).isEqualTo("COMPLETED");
        assertThat(finalStatus(s.enrollmentId())).isEqualTo("C");
        assertThat(actions(s.enrollmentId(), "ENROLLMENT_FINAL_GRADE_CORRECTED")).isEqualTo(1);
        List<String> events = completedEvents(s.enrollmentId());
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).contains("\"corrected\": true").contains("\"passed\": true").contains("\"finalGrade\": \"C\"");
    }

    @Test
    void theSameGradeAgainChangesNothing() {
        var s = scenario("FG5");
        publish(s, "B");

        var outcome = publish(s, "b");

        assertThat(outcome).isEqualTo(FinalGradeEventProcessor.Outcome.UNCHANGED);
        assertThat(completedEvents(s.enrollmentId())).hasSize(1);
        assertThat(actions(s.enrollmentId(), "ENROLLMENT_COMPLETED")).isEqualTo(1);
    }

    @Test
    void aRedeliveredEventDoesNothing() {
        var s = scenario("FG6");
        UUID eventId = UUID.randomUUID();
        String tenant = s.tenant().tenantId().toString();
        String body = payload(eventId, s.tenant().tenantId(), s.enrollmentId(), "A", s.faculty().userId());
        processor.process("FinalGradePublished", eventId.toString(), tenant, body);

        var again = processor.process("FinalGradePublished", eventId.toString(), tenant, body);

        assertThat(again).isEqualTo(FinalGradeEventProcessor.Outcome.DUPLICATE);
        assertThat(completedEvents(s.enrollmentId())).hasSize(1);
    }

    @Test
    void aDroppedEnrollmentIsLeftAlone() {
        var s = scenario("FG7");
        jdbc.sql("UPDATE enrollment.enrollments SET status = 'DROPPED', dropped_at = now() WHERE id = :id").param("id", s.enrollmentId()).update();

        var outcome = publish(s, "A");

        assertThat(outcome).isEqualTo(FinalGradeEventProcessor.Outcome.NOT_ENROLLED);
        assertThat(status(s.enrollmentId())).isEqualTo("DROPPED");
        assertThat(finalStatus(s.enrollmentId())).isNull();
        assertThat(completedEvents(s.enrollmentId())).isEmpty();
    }

    @Test
    void anEventNamingAnotherCollegesTenantIsRefusedAndLeavesNothingBehind() {
        var s = scenario("FG8");
        var other = fixtures.provisionEnrolledScenario("FG8X");
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process("FinalGradePublished", eventId.toString(), other.tenant().tenantId().toString(),
            payload(eventId, other.tenant().tenantId(), s.enrollmentId(), "A", s.faculty().userId()))).isInstanceOf(PoisonEventException.class);

        assertThat(status(s.enrollmentId())).isEqualTo("ENROLLED");
        assertThat(processed(eventId)).as("the guard rolled back with the refusal").isZero();
    }

    @Test
    void aTenantHeaderThatDisagreesWithThePayloadIsRefused() {
        var s = scenario("FG9");
        var other = fixtures.provisionEnrolledScenario("FG9X");
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process("FinalGradePublished", eventId.toString(), other.tenant().tenantId().toString(),
            payload(eventId, s.tenant().tenantId(), s.enrollmentId(), "A", s.faculty().userId()))).isInstanceOf(PoisonEventException.class);

        assertThat(status(s.enrollmentId())).isEqualTo("ENROLLED");
    }

    @Test
    void anUnknownEnrollmentIsRetriableNotPoison() {
        var s = scenario("FG10");
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process("FinalGradePublished", eventId.toString(), s.tenant().tenantId().toString(),
            payload(eventId, s.tenant().tenantId(), UUID.randomUUID(), "A", s.faculty().userId()))).isInstanceOf(EnrollmentNotKnownException.class);

        assertThat(processed(eventId)).as("not recorded as handled, so the retry can succeed").isZero();
    }

    @Test
    void malformedPayloadsArePoison() {
        UUID tenant = UUID.randomUUID();
        UUID enrollment = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        String base = "\"eventId\":\"" + event + "\",\"tenantId\":\"" + tenant + "\",\"enrollmentId\":\"" + enrollment + "\"";
        for (String bad : List.of("not json", "[]", "{}", "{" + base + "}", "{" + base + ",\"gradeCode\":\"A\"}",
            "{" + base + ",\"gradeCode\":\"A\",\"publishedBy\":\"nope\"}", "{" + base + ",\"gradeCode\":\" \",\"publishedBy\":\"" + user + "\"}")) {
            assertThatThrownBy(() -> processor.process("FinalGradePublished", null, null, bad)).as(bad).isInstanceOf(PoisonEventException.class);
        }
    }

    @Test
    void anEventThatIsNotAFinalGradeIsIgnoredAndNotRecorded() {
        UUID eventId = UUID.randomUUID();

        var outcome = processor.process("GradePublished", eventId.toString(), null, "{\"anything\":true}");

        assertThat(outcome).isEqualTo(FinalGradeEventProcessor.Outcome.IGNORED);
        assertThat(processed(eventId)).isZero();
    }

    @Test
    void eventsRacingForOneEnrollmentCompleteItOnce() throws Exception {
        var s = scenario("FG11");
        int racers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<FinalGradeEventProcessor.Outcome>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return publish(s, "A");
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            List<FinalGradeEventProcessor.Outcome> outcomes = new ArrayList<>();
            for (Future<FinalGradeEventProcessor.Outcome> f : results) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(o -> o == FinalGradeEventProcessor.Outcome.COMPLETED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == FinalGradeEventProcessor.Outcome.UNCHANGED).hasSize(racers - 1);
            assertThat(completedEvents(s.enrollmentId())).hasSize(1);
            assertThat(actions(s.enrollmentId(), "ENROLLMENT_COMPLETED")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
