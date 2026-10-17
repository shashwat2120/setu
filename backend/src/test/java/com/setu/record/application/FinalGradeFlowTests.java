package com.setu.record.application;

import com.setu.assessment.application.AssessmentComponentService;
import com.setu.assessment.application.AssessmentService;
import com.setu.assessment.application.GradeService;
import com.setu.assessment.application.QuestionService;
import com.setu.assessment.domain.QuestionType;
import com.setu.enrollment.application.FinalGradeEventProcessor;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The college's decision end to end, in one process and without a broker: a faculty member publishes the section's final grade through the real
 * grade service, the {@code FinalGradePublished} event it wrote is handed to the enrollment consumer's processor exactly as the listener would hand
 * it, the enrollment is completed, and the student's transcript, GPA and degree progress follow. A failing final grade completes the enrollment but
 * earns no credit, and a correction changes the outcome.
 */
@SpringBootTest
class FinalGradeFlowTests {

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    AssessmentService assessmentService;
    @Autowired
    AssessmentComponentService componentService;
    @Autowired
    QuestionService questionService;
    @Autowired
    GradeService gradeService;
    @Autowired
    FinalGradeEventProcessor processor;
    @Autowired
    AcademicRecordService records;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    /** A scenario whose fixture events are marked handled, so a running backend's own consumers (the development database is shared) leave them be. */
    private Day13TestFixtures.Scenario scenario(String prefix) {
        var s = fixtures.provisionEnrolledScenario(prefix);
        jdbc.sql("INSERT INTO enrollment.processed_events (event_id, event_type) SELECT id, event_type FROM governance.outbox_events WHERE tenant_id = :t ON CONFLICT (event_id) DO NOTHING")
            .param("t", s.tenant().tenantId()).update();
        return s;
    }

    private UUID finalExam(Day13TestFixtures.Scenario s) {
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var assessment = (AssessmentService.CreateResult.Success) assessmentService.create(s.sectionId(), "EXAM", "Final exam", new BigDecimal("100"), 60,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
            var component = (AssessmentComponentService.CreateResult.Success) componentService.create(assessment.assessmentId(), "FINAL",
                new BigDecimal("100"), new BigDecimal("100"), 1, true);
            questionService.create(component.componentId(), QuestionType.MCQ_SINGLE, "What is 2+2?", new BigDecimal("100"), "EASY", null, "{\"correctOption\": \"B\"}");
            assessmentService.schedule(assessment.assessmentId());
            assessmentService.open(assessment.assessmentId());
            return component.componentId();
        } finally {
            TestAuthentication.clear();
        }
    }

    private void publish(Day13TestFixtures.Scenario s, UUID componentId, String gradeCode, String gradePoint) {
        UUID assessmentId = jdbc.sql("SELECT assessment_id FROM assessment.assessment_components WHERE id = :id").param("id", componentId).query(UUID.class).single();
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var result = gradeService.publishOrCorrect(assessmentId, componentId, s.student().studentId(), new BigDecimal("80"), gradeCode, new BigDecimal(gradePoint));
            assertThat(result).isInstanceOf(GradeService.PublishResult.Success.class);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** What the listener does with the newest FinalGradePublished the grade service wrote for this enrollment. */
    private FinalGradeEventProcessor.Outcome deliverLatestFinalGrade(Day13TestFixtures.Scenario s) {
        Map<String, Object> row = jdbc.sql("SELECT id, payload_json::text AS payload FROM governance.outbox_events WHERE event_type = 'FinalGradePublished' AND aggregate_id = :e ORDER BY occurred_at DESC LIMIT 1")
            .param("e", s.enrollmentId()).query().singleRow();
        return processor.process("FinalGradePublished", row.get("id").toString(), s.tenant().tenantId().toString(), (String) row.get("payload"));
    }

    private String status(Day13TestFixtures.Scenario s) {
        return jdbc.sql("SELECT status || ':' || coalesce(final_status, '-') FROM enrollment.enrollments WHERE id = :id").param("id", s.enrollmentId()).query(String.class).single();
    }

    @Test
    void publishingAPassingFinalGradeCompletesTheEnrollmentAndEarnsTheCredits() {
        var s = scenario("FF1");
        UUID component = finalExam(s);
        assertThat(status(s)).isEqualTo("ENROLLED:-");

        publish(s, component, "B", "3.0");
        var outcome = deliverLatestFinalGrade(s);

        assertThat(outcome).isEqualTo(FinalGradeEventProcessor.Outcome.COMPLETED);
        assertThat(status(s)).isEqualTo("COMPLETED:B");
        TestAuthentication.authenticateAs(s.student().userId());
        var transcript = records.transcript(s.student().studentId(), s.tenant().tenantId());
        assertThat(transcript.lines()).hasSize(1);
        assertThat(transcript.lines().get(0).enrollmentStatus()).isEqualTo("COMPLETED");
        assertThat(transcript.lines().get(0).finalGrade()).isEqualTo("B");
        assertThat(transcript.gpa()).isEqualByComparingTo("3.00");
        assertThat(records.degreeProgress(s.student().studentId(), s.tenant().tenantId()).creditsCompleted()).isEqualByComparingTo("4.0");
    }

    @Test
    void aFailingFinalGradeCompletesTheEnrollmentButEarnsNothing() {
        var s = scenario("FF2");
        UUID component = finalExam(s);

        publish(s, component, "F", "0.0");
        deliverLatestFinalGrade(s);

        assertThat(status(s)).isEqualTo("COMPLETED:F");
        TestAuthentication.authenticateAs(s.student().userId());
        assertThat(records.transcript(s.student().studentId(), s.tenant().tenantId()).gpa()).as("a fail counts in the GPA at its grade points").isEqualByComparingTo("0.00");
        assertThat(records.degreeProgress(s.student().studentId(), s.tenant().tenantId()).creditsCompleted()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void correctingAFailToAPassEarnsTheCredits() {
        var s = scenario("FF3");
        UUID component = finalExam(s);
        publish(s, component, "F", "0.0");
        deliverLatestFinalGrade(s);

        publish(s, component, "C", "2.0");
        var outcome = deliverLatestFinalGrade(s);

        assertThat(outcome).isEqualTo(FinalGradeEventProcessor.Outcome.CORRECTED);
        assertThat(status(s)).as("still completed, with the corrected grade").isEqualTo("COMPLETED:C");
        TestAuthentication.authenticateAs(s.student().userId());
        assertThat(records.degreeProgress(s.student().studentId(), s.tenant().tenantId()).creditsCompleted()).isEqualByComparingTo("4.0");
    }
}
