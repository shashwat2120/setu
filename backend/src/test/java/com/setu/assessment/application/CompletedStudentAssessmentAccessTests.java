package com.setu.assessment.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day11TestFixtures;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a student can do with a course's assessments after the course is over. A final grade completes the enrollment (docs/state-machines.md #11);
 * the student keeps reading the course's assessments and results, and cannot start anything new. A student who dropped has neither. Not
 * {@code @Transactional}: the services open their own transactions.
 */
@SpringBootTest
class CompletedStudentAssessmentAccessTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day11TestFixtures day11Fixtures;
    @Autowired
    AssessmentService assessmentService;
    @Autowired
    AssessmentAttemptService assessmentAttemptService;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Course(Day13TestFixtures.Scenario scenario, java.util.UUID assessmentId) {
    }

    private Course setUp(String prefix) {
        var s = day13Fixtures.provisionEnrolledScenario(prefix);
        var open = day11Fixtures.provisionOpenAssessment(s.faculty().userId(), s.sectionId(), prefix + "-Exam");
        TestAuthentication.clear();
        return new Course(s, open.assessmentId());
    }

    private void setStatus(Course c, String status) {
        jdbc.sql("UPDATE enrollment.enrollments SET status = :s, final_status = :f WHERE id = :id")
            .param("s", status).param("f", "COMPLETED".equals(status) ? "B" : null).param("id", c.scenario().enrollmentId()).update();
    }

    @Test
    void aStudentWhoFinishedTheCourseStillReadsItsAssessments() {
        var c = setUp("CSA1");
        setStatus(c, "COMPLETED");
        TestAuthentication.authenticateAs(c.scenario().student().userId());

        assertThat(assessmentService.listForSection(c.scenario().sectionId(), c.scenario().tenant().tenantId()))
            .extracting(a -> a.id()).contains(c.assessmentId());
        assertThat(assessmentService.get(c.assessmentId())).isPresent();
    }

    @Test
    void aStudentWhoFinishedTheCourseCannotStartAnAttemptAtItsOpenAssessment() {
        var c = setUp("CSA2");
        setStatus(c, "COMPLETED");
        TestAuthentication.authenticateAs(c.scenario().student().userId());

        assertThat(assessmentAttemptService.start(c.assessmentId())).isInstanceOf(AssessmentAttemptService.StartResult.NotEnrolled.class);
    }

    @Test
    void aStudentStillInTheCourseCanStartAnAttempt() {
        var c = setUp("CSA3");
        TestAuthentication.authenticateAs(c.scenario().student().userId());

        assertThat(assessmentAttemptService.start(c.assessmentId())).isInstanceOf(AssessmentAttemptService.StartResult.Success.class);
    }

    @Test
    void aStudentWhoDroppedTheCourseReadsNothingOfItsAssessments() {
        var c = setUp("CSA4");
        setStatus(c, "DROPPED");
        TestAuthentication.authenticateAs(c.scenario().student().userId());

        assertThatThrownBy(() -> assessmentService.listForSection(c.scenario().sectionId(), c.scenario().tenant().tenantId()))
            .isInstanceOf(AccessDeniedException.class);
    }
}
