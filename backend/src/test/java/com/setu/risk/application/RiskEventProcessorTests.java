package com.setu.risk.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
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
 * Risk recalculation driven by a grade event (canonical spec section 16: grade event -> risk recalculation), independent of Kafka: the payload
 * is the real outbox row the real {@code GradeService} wrote, handed to the processor the way the listener would. Deliberately not
 * {@code @Transactional}: the processor owns its transaction, and the concurrency test needs real commits.
 * <p>
 * Covers: a published grade recalculates the student with no signed-in user; a redelivered event does nothing; a different event that changes
 * nothing the rule looks at records nothing; a new grade records a new score; every refusal (wrong tenant, unknown enrollment, malformed
 * payload) leaves nothing behind, including the processed-event record; events that are not triggers are ignored; and two events for one student
 * racing do not both write a score.
 */
@SpringBootTest
class RiskEventProcessorTests {

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    RiskEventProcessor processor;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    /**
     * The development database is shared with a running backend whose own risk consumer would otherwise also act on the events these fixtures
     * write and add scores while a test is counting them. Mark the fixture's events as already handled, so a test only ever sees the
     * recalculations it asks for. (A clean CI run has no such consumer; this is then a harmless no-op.)
     */
    private void keepLiveConsumerOut(UUID tenantId) {
        jdbc.sql("INSERT INTO risk.processed_events (event_id, event_type) SELECT id, event_type FROM governance.outbox_events WHERE tenant_id = :t ON CONFLICT (event_id) DO NOTHING")
            .param("t", tenantId).update();
    }

    private Day13TestFixtures.Scenario scenarioWithGrade(String prefix, String gradeCode, String gradePoint) {
        var s = fixtures.provisionEnrolledScenario(prefix);
        fixtures.recordGrade(s, gradeCode, new BigDecimal(gradePoint));
        keepLiveConsumerOut(s.tenant().tenantId());
        TestAuthentication.clear();
        return s;
    }

    /** The payload of the newest GradePublished the real grade service wrote for this tenant. */
    private String gradePublishedPayload(UUID tenantId) {
        return jdbc.sql("SELECT payload_json::text FROM governance.outbox_events WHERE tenant_id = :t AND event_type = 'GradePublished' ORDER BY occurred_at DESC LIMIT 1")
            .param("t", tenantId).query(String.class).single();
    }

    private int scores(UUID studentId) {
        return jdbc.sql("SELECT count(*) FROM risk.risk_scores WHERE student_id = :s").param("s", studentId).query(Integer.class).single();
    }

    private int processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM risk.processed_events WHERE event_id = :e").param("e", eventId).query(Integer.class).single();
    }

    private int riskScoreChangedEvents(UUID studentId) {
        return jdbc.sql("SELECT count(*) FROM governance.outbox_events WHERE event_type = 'RiskScoreChanged' AND aggregate_id = :s")
            .param("s", studentId).query(Integer.class).single();
    }

    @Test
    void aPublishedGradeRecalculatesTheStudentsRiskWithNoOneSignedIn() {
        var s = scenarioWithGrade("RE1", "F", "0");
        UUID studentId = s.student().studentId();
        UUID eventId = UUID.randomUUID();
        assertThat(scores(studentId)).isZero();

        var outcome = processor.process("GradePublished", eventId.toString(), s.tenant().tenantId().toString(), gradePublishedPayload(s.tenant().tenantId()));

        assertThat(outcome).isEqualTo(RiskEventProcessor.Outcome.RECALCULATED);
        assertThat(scores(studentId)).isEqualTo(1);
        // The same stored evidence a person pressing Recalculate would get: rule version, snapshot hash, factors.
        var row = jdbc.sql("SELECT rule_version, feature_snapshot_hash, tenant_id FROM risk.risk_scores WHERE student_id = :s").param("s", studentId).query().singleRow();
        assertThat(row.get("rule_version")).isEqualTo("v1");
        assertThat((String) row.get("feature_snapshot_hash")).hasSize(64);
        assertThat(row.get("tenant_id")).isEqualTo(s.tenant().tenantId());
        assertThat(jdbc.sql("SELECT count(*) FROM risk.risk_factors f JOIN risk.risk_scores r ON r.id = f.risk_score_id WHERE r.student_id = :s")
            .param("s", studentId).query(Integer.class).single()).isGreaterThan(0);
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void theAuditEntryHasNoActorAndNamesTheEvent() {
        var s = scenarioWithGrade("RE2", "F", "0");
        UUID eventId = UUID.randomUUID();

        processor.process("GradePublished", eventId.toString(), s.tenant().tenantId().toString(), gradePublishedPayload(s.tenant().tenantId()));

        var audit = jdbc.sql("SELECT actor_user_id, after_json::text AS details FROM governance.audit_logs a JOIN risk.risk_scores r ON r.id = a.resource_id "
                + "WHERE a.action = 'RISK_SCORE_CALCULATED' AND r.student_id = :s")
            .param("s", s.student().studentId()).query().singleRow();
        assertThat(audit.get("actor_user_id")).as("a system calculation has no actor").isNull();
        assertThat((String) audit.get("details")).contains("GradePublished").contains(eventId.toString());
    }

    @Test
    void aRedeliveredEventDoesNothing() {
        var s = scenarioWithGrade("RE3", "F", "0");
        UUID eventId = UUID.randomUUID();
        String tenant = s.tenant().tenantId().toString();
        String payload = gradePublishedPayload(s.tenant().tenantId());
        processor.process("GradePublished", eventId.toString(), tenant, payload);

        var again = processor.process("GradePublished", eventId.toString(), tenant, payload);

        assertThat(again).isEqualTo(RiskEventProcessor.Outcome.DUPLICATE);
        assertThat(scores(s.student().studentId())).isEqualTo(1);
        assertThat(riskScoreChangedEvents(s.student().studentId())).as("one alert, not two").isEqualTo(1);
    }

    @Test
    void aSecondEventThatChangesNothingTheRuleLooksAtRecordsNothing() {
        var s = scenarioWithGrade("RE4", "F", "0");
        String tenant = s.tenant().tenantId().toString();
        String payload = gradePublishedPayload(s.tenant().tenantId());
        processor.process("GradePublished", UUID.randomUUID().toString(), tenant, payload);

        var outcome = processor.process("GradePublished", UUID.randomUUID().toString(), tenant, payload);

        assertThat(outcome).isEqualTo(RiskEventProcessor.Outcome.UNCHANGED);
        assertThat(scores(s.student().studentId())).as("history is not padded with identical rows").isEqualTo(1);
    }

    @Test
    void aNewGradeThatChangesTheInputsRecordsANewScore() {
        var s = scenarioWithGrade("RE5", "F", "0");
        String tenant = s.tenant().tenantId().toString();
        processor.process("GradePublished", UUID.randomUUID().toString(), tenant, gradePublishedPayload(s.tenant().tenantId()));

        fixtures.recordGrade(s, "A", new BigDecimal("4.0"));
        keepLiveConsumerOut(s.tenant().tenantId());
        TestAuthentication.clear();
        var outcome = processor.process("GradePublished", UUID.randomUUID().toString(), tenant, gradePublishedPayload(s.tenant().tenantId()));

        assertThat(outcome).isEqualTo(RiskEventProcessor.Outcome.RECALCULATED);
        assertThat(scores(s.student().studentId())).as("append-only: the first score is still there").isEqualTo(2);
    }

    @Test
    void anEventNamingAnotherCollegesTenantIsRefusedAndLeavesNothingBehind() {
        var s = scenarioWithGrade("RE6", "F", "0");
        var other = fixtures.provisionEnrolledScenario("RE6X");
        UUID eventId = UUID.randomUUID();
        // The enrollment is this tenant's; the event claims it belongs to another college.
        String forged = gradePublishedPayload(s.tenant().tenantId()).replace(s.tenant().tenantId().toString(), other.tenant().tenantId().toString());

        assertThatThrownBy(() -> processor.process("GradePublished", eventId.toString(), other.tenant().tenantId().toString(), forged))
            .isInstanceOf(PoisonEventException.class);

        assertThat(scores(s.student().studentId())).isZero();
        assertThat(scores(other.student().studentId())).isZero();
        assertThat(processed(eventId)).as("the guard rolled back with the refusal").isZero();
    }

    @Test
    void aTenantHeaderThatDisagreesWithThePayloadIsRefused() {
        var s = scenarioWithGrade("RE7", "F", "0");
        var other = fixtures.provisionEnrolledScenario("RE7X");

        assertThatThrownBy(() -> processor.process("GradePublished", UUID.randomUUID().toString(), other.tenant().tenantId().toString(),
            gradePublishedPayload(s.tenant().tenantId()))).isInstanceOf(PoisonEventException.class);

        assertThat(scores(s.student().studentId())).isZero();
    }

    @Test
    void anUnknownEnrollmentIsRetriableNotPoison() {
        var s = scenarioWithGrade("RE8", "F", "0");
        UUID eventId = UUID.randomUUID();
        String unknown = gradePublishedPayload(s.tenant().tenantId()).replaceAll("\"enrollmentId\":\\s*\"[0-9a-f-]+\"", "\"enrollmentId\": \"" + UUID.randomUUID() + "\"");
        assertThat(unknown).as("the payload really was changed").doesNotContain(s.enrollmentId().toString());

        assertThatThrownBy(() -> processor.process("GradePublished", eventId.toString(), s.tenant().tenantId().toString(), unknown))
            .isInstanceOf(StudentNotKnownException.class);

        assertThat(processed(eventId)).as("not recorded as handled, so the retry can succeed").isZero();
    }

    @Test
    void malformedPayloadsArePoison() {
        UUID tenant = UUID.randomUUID();
        for (String bad : List.of("not json", "[]", "{}", "{\"eventId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + tenant + "\"}",
            "{\"eventId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + tenant + "\",\"enrollmentId\":\"nope\"}")) {
            assertThatThrownBy(() -> processor.process("GradePublished", null, null, bad)).as(bad).isInstanceOf(PoisonEventException.class);
        }
    }

    @Test
    void anEventThatIsNotATriggerIsIgnoredAndNotRecorded() {
        UUID eventId = UUID.randomUUID();

        var outcome = processor.process("PaymentSuccessful", eventId.toString(), null, "{\"anything\":true}");

        assertThat(outcome).isEqualTo(RiskEventProcessor.Outcome.IGNORED);
        assertThat(processed(eventId)).isZero();
    }

    @Test
    void twoEventsForOneStudentRacingWriteOneScoreAndRaiseOneAlert() throws Exception {
        var s = scenarioWithGrade("RE9", "F", "0");
        String tenant = s.tenant().tenantId().toString();
        String payload = gradePublishedPayload(s.tenant().tenantId());
        int racers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<RiskEventProcessor.Outcome>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return processor.process("GradePublished", UUID.randomUUID().toString(), tenant, payload);
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            List<RiskEventProcessor.Outcome> outcomes = new ArrayList<>();
            for (Future<RiskEventProcessor.Outcome> f : results) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(o -> o == RiskEventProcessor.Outcome.RECALCULATED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == RiskEventProcessor.Outcome.UNCHANGED).hasSize(racers - 1);
            assertThat(scores(s.student().studentId())).isEqualTo(1);
            assertThat(riskScoreChangedEvents(s.student().studentId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
