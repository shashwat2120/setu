package com.setu.assessment.application;

import com.setu.assessment.domain.QuestionType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The final grade: one component of a section is marked as the course's final grade, and publishing a grade for it (or correcting that grade)
 * is announced as {@code FinalGradePublished}, which completes the student's enrollment (a decision of the college). Covers the component rule (one
 * per section, even when created at once), the event, and that grading carries on after the enrollment is completed but not for a student who
 * dropped. Not {@code @Transactional}: the concurrency test needs real commits.
 */
@SpringBootTest
class FinalGradeComponentTests {

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
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private UUID draftAssessment(Day13TestFixtures.Scenario s, String title) {
        TestAuthentication.authenticateAs(s.faculty().userId());
        var created = (AssessmentService.CreateResult.Success) assessmentService.create(s.sectionId(), "EXAM", title, new BigDecimal("100"), 60,
            Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
        return created.assessmentId();
    }

    private AssessmentComponentService.CreateResult component(UUID assessmentId, String code, boolean finalGrade) {
        return componentService.create(assessmentId, code, new BigDecimal("100"), new BigDecimal("40"), 1, finalGrade);
    }

    /** An open assessment with one component, final or not, ready to have grades published for it. */
    private UUID openComponent(Day13TestFixtures.Scenario s, String title, boolean finalGrade) {
        UUID assessmentId = draftAssessment(s, title);
        var component = (AssessmentComponentService.CreateResult.Success) component(assessmentId, "C1", finalGrade);
        questionService.create(component.componentId(), QuestionType.MCQ_SINGLE, "What is 2+2?", new BigDecimal("100"), "EASY", null, "{\"correctOption\": \"B\"}");
        assessmentService.schedule(assessmentId);
        assessmentService.open(assessmentId);
        TestAuthentication.clear();
        return component.componentId();
    }

    private UUID assessmentOf(UUID componentId) {
        return jdbc.sql("SELECT assessment_id FROM assessment.assessment_components WHERE id = :id").param("id", componentId).query(UUID.class).single();
    }

    private GradeService.PublishResult publish(Day13TestFixtures.Scenario s, UUID componentId, String gradeCode, String gradePoint) {
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            return gradeService.publishOrCorrect(assessmentOf(componentId), componentId, s.student().studentId(), new BigDecimal("80"), gradeCode,
                new BigDecimal(gradePoint));
        } finally {
            TestAuthentication.clear();
        }
    }

    private List<String> finalGradeEvents(UUID enrollmentId) {
        return jdbc.sql("SELECT payload_json::text FROM governance.outbox_events WHERE event_type = 'FinalGradePublished' AND aggregate_id = :id ORDER BY occurred_at")
            .param("id", enrollmentId).query(String.class).list();
    }

    @Test
    void aComponentCanBeMarkedAsTheFinalGrade() {
        var s = fixtures.provisionEnrolledScenario("FC1");
        UUID assessmentId = draftAssessment(s, "Final exam");

        var result = component(assessmentId, "FINAL", true);

        assertThat(result).isInstanceOf(AssessmentComponentService.CreateResult.Success.class);
        UUID componentId = ((AssessmentComponentService.CreateResult.Success) result).componentId();
        assertThat(componentService.get(componentId).orElseThrow().finalGrade()).isTrue();
        assertThat(componentService.create(assessmentId, "QUIZ", new BigDecimal("10"), new BigDecimal("10"), 2)
            instanceof AssessmentComponentService.CreateResult.Success).as("the old signature still makes an ordinary component").isTrue();
        assertThat(componentService.listForAssessment(assessmentId)).extracting(c -> c.code() + ":" + c.finalGrade())
            .containsExactlyInAnyOrder("FINAL:true", "QUIZ:false");
    }

    @Test
    void aSectionHasOneFinalGradeComponentEvenAcrossAssessments() {
        var s = fixtures.provisionEnrolledScenario("FC2");
        UUID first = draftAssessment(s, "Midterm");
        UUID second = draftAssessment(s, "Final exam");
        assertThat(component(first, "FINAL", true)).isInstanceOf(AssessmentComponentService.CreateResult.Success.class);

        assertThat(component(second, "FINAL", true)).as("a course has one final grade").isInstanceOf(AssessmentComponentService.CreateResult.FinalGradeComponentExists.class);
        assertThat(component(first, "OTHER", true)).isInstanceOf(AssessmentComponentService.CreateResult.FinalGradeComponentExists.class);
        assertThat(component(second, "PROJECT", false)).as("ordinary components are unaffected").isInstanceOf(AssessmentComponentService.CreateResult.Success.class);
    }

    @Test
    void anotherSectionHasItsOwnFinalGradeComponent() {
        var one = fixtures.provisionEnrolledScenario("FC3");
        var two = fixtures.provisionEnrolledScenario("FC3X");
        assertThat(component(draftAssessment(one, "Final"), "FINAL", true)).isInstanceOf(AssessmentComponentService.CreateResult.Success.class);

        assertThat(component(draftAssessment(two, "Final"), "FINAL", true)).isInstanceOf(AssessmentComponentService.CreateResult.Success.class);
    }

    @Test
    void finalGradeComponentsCreatedAtOnceCannotBothSucceed() throws Exception {
        var s = fixtures.provisionEnrolledScenario("FC4");
        int racers = 4;
        List<UUID> assessments = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            assessments.add(draftAssessment(s, "Final " + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<AssessmentComponentService.CreateResult>> futures = new ArrayList<>();
            for (UUID assessmentId : assessments) {
                futures.add(pool.submit(() -> {
                    TestAuthentication.authenticateAs(s.faculty().userId());
                    try {
                        ready.countDown();
                        go.await(30, TimeUnit.SECONDS);
                        return component(assessmentId, "FINAL", true);
                    } finally {
                        TestAuthentication.clear();
                    }
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            List<AssessmentComponentService.CreateResult> results = new ArrayList<>();
            for (var f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(r -> r instanceof AssessmentComponentService.CreateResult.Success).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof AssessmentComponentService.CreateResult.FinalGradeComponentExists).hasSize(racers - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFinalGradeIsAnnouncedWithWhatTheEnrollmentNeedsAndKeyedByTheEnrollment() {
        var s = fixtures.provisionEnrolledScenario("FC5");
        UUID componentId = openComponent(s, "Final exam", true);

        var result = publish(s, componentId, "B", "3.0");

        assertThat(result).isInstanceOf(GradeService.PublishResult.Success.class);
        List<String> events = finalGradeEvents(s.enrollmentId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).contains("\"gradeCode\": \"B\"").contains("\"version\": 1").contains(s.enrollmentId().toString())
            .contains(s.faculty().userId().toString()).contains(s.tenant().tenantId().toString());
        assertThat(jdbc.sql("SELECT count(*) FROM governance.outbox_events WHERE event_type = 'GradePublished' AND payload_json->>'enrollmentId' = :e")
            .param("e", s.enrollmentId().toString()).query(Integer.class).single()).as("GradePublished still announces every grade").isEqualTo(1);
    }

    @Test
    void aCorrectionOfTheFinalGradeIsAnnouncedToo() {
        var s = fixtures.provisionEnrolledScenario("FC6");
        UUID componentId = openComponent(s, "Final exam", true);
        publish(s, componentId, "F", "0.0");

        var correction = publish(s, componentId, "C", "2.0");

        assertThat(((GradeService.PublishResult.Success) correction).isCorrection()).isTrue();
        List<String> events = finalGradeEvents(s.enrollmentId());
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).contains("\"gradeCode\": \"C\"").contains("\"version\": 2");
        assertThat(events.get(1)).doesNotContain("\"supersedesId\": null");
    }

    @Test
    void aGradeForAnOrdinaryComponentIsNotAFinalGrade() {
        var s = fixtures.provisionEnrolledScenario("FC7");
        UUID componentId = openComponent(s, "Quiz", false);

        publish(s, componentId, "A", "4.0");

        assertThat(finalGradeEvents(s.enrollmentId())).isEmpty();
    }

    @Test
    void gradingCarriesOnAfterTheEnrollmentIsCompleted() {
        var s = fixtures.provisionEnrolledScenario("FC8");
        UUID finalComponent = openComponent(s, "Final exam", true);
        UUID lateComponent = openComponent(s, "Late project", false);
        publish(s, finalComponent, "B", "3.0");
        jdbc.sql("UPDATE enrollment.enrollments SET status = 'COMPLETED', final_status = 'B' WHERE id = :id").param("id", s.enrollmentId()).update();

        assertThat(publish(s, lateComponent, "A", "4.0")).as("a late component").isInstanceOf(GradeService.PublishResult.Success.class);
        assertThat(publish(s, finalComponent, "A", "4.0")).as("a correction of the final grade").isInstanceOf(GradeService.PublishResult.Success.class);
    }

    @Test
    void aStudentWhoDroppedCannotBeGraded() {
        var s = fixtures.provisionEnrolledScenario("FC9");
        UUID componentId = openComponent(s, "Quiz", false);
        jdbc.sql("UPDATE enrollment.enrollments SET status = 'DROPPED', dropped_at = now() WHERE id = :id").param("id", s.enrollmentId()).update();

        assertThat(publish(s, componentId, "A", "4.0")).isInstanceOf(GradeService.PublishResult.NotEnrolled.class);
    }
}
