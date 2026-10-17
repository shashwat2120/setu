package com.setu.record.application;

import com.setu.identity.TestAuthentication;
import com.setu.shared.academic.RecordDirectory;
import com.setu.testsupport.Day13TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The academic record reads Academic through {@link RecordDirectory} and nothing else (it imports none of Academic's classes). These pin what
 * the port answers and that the record's numbers are what they were when it read Academic's services directly: the transcript lists a student's
 * enrollments with their course, the GPA is credit-weighted from the published grades, degree progress counts only COMPLETED enrollments, and
 * one student's record never contains another's.
 */
@SpringBootTest
class RecordDirectoryTests {

    @Autowired
    Day13TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    RecordDirectory directory;
    @Autowired
    AcademicRecordService records;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void aStudentsEnrollmentsComeWithTheirCourse() {
        var s = fixtures.provisionEnrolledScenario("RD1");

        var enrollments = directory.enrollmentsOf(s.student().studentId());

        assertThat(enrollments).hasSize(1);
        var e = enrollments.get(0);
        assertThat(e.enrollmentId()).isEqualTo(s.enrollmentId());
        assertThat(e.status()).isEqualTo("ENROLLED");
        assertThat(e.courseCode()).isEqualTo("RD1C");
        assertThat(e.courseTitle()).isEqualTo("RD1C Title");
        assertThat(e.credits()).isEqualByComparingTo("4.0");
        assertThat(e.sectionCode()).isNotBlank();
        assertThat(e.finalGrade()).as("no final grade until one is published").isNull();
        assertThat(e.passed()).isFalse();
    }

    @Test
    void oneStudentsRecordNeverContainsAnotherStudentsEnrollments() {
        var s = fixtures.provisionEnrolledScenario("RD2");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant(), s.programId(), s.curriculumId(), "RD2O");

        assertThat(directory.enrollmentsOf(other.studentId())).isEmpty();
        assertThat(directory.enrollmentsOf(s.student().studentId())).extracting(RecordDirectory.CourseEnrollment::enrollmentId)
            .containsExactly(s.enrollmentId());
        assertThat(directory.enrollmentsOf(UUID.randomUUID())).as("an unknown student has no enrollments, not an error").isEmpty();
    }

    @Test
    void theStudentsCurriculumAndItsRequiredCreditsAreAnswered() {
        var s = fixtures.provisionEnrolledScenario("RD3");

        assertThat(directory.activeCurriculumOf(s.student().studentId())).contains(s.curriculumId());
        assertThat(directory.requiredCredits(s.curriculumId())).as("a curriculum with no stated minimum requires nothing").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(directory.activeCurriculumOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theTranscriptShowsThePublishedGradeAndAGpaWeightedByCredits() {
        var s = fixtures.provisionEnrolledScenario("RD4");
        fixtures.recordGrade(s, "A", new BigDecimal("4.0"));
        TestAuthentication.authenticateAs(s.student().userId());

        var transcript = records.transcript(s.student().studentId(), s.tenant().tenantId());

        assertThat(transcript.lines()).hasSize(1);
        var line = transcript.lines().get(0);
        assertThat(line.courseCode()).isEqualTo("RD4C");
        assertThat(line.credits()).isEqualByComparingTo("4.0");
        assertThat(line.grades()).hasSize(1);
        assertThat(line.courseGradePoint()).isEqualByComparingTo("4.00");
        assertThat(transcript.gpa()).isEqualByComparingTo("4.00");
    }

    @Test
    void degreeProgressCountsOnlyCompletedEnrollments() {
        var s = fixtures.provisionEnrolledScenario("RD5");
        TestAuthentication.authenticateAs(s.student().userId());
        var before = records.degreeProgress(s.student().studentId(), s.tenant().tenantId());
        assertThat(before.hasActiveProgram()).isTrue();
        assertThat(before.creditsCompleted()).as("an enrollment in progress is not credit earned").isEqualByComparingTo(BigDecimal.ZERO);

        complete(s, "B");
        var after = records.degreeProgress(s.student().studentId(), s.tenant().tenantId());

        assertThat(after.creditsCompleted()).isEqualByComparingTo("4.0");
    }

    /** What publishing a final grade does to the enrollment (FinalGradeFlowTests drives the real path); here the state is set directly. */
    private void complete(Day13TestFixtures.Scenario s, String finalGrade) {
        jdbc.sql("UPDATE enrollment.enrollments SET status = 'COMPLETED', final_status = :g WHERE id = :id")
            .param("g", finalGrade).param("id", s.enrollmentId()).update();
    }

    @Test
    void aFailedCourseEarnsNoCredits() {
        var s = fixtures.provisionEnrolledScenario("RD7");
        complete(s, "F");
        TestAuthentication.authenticateAs(s.student().userId());

        var progress = records.degreeProgress(s.student().studentId(), s.tenant().tenantId());
        var line = records.transcript(s.student().studentId(), s.tenant().tenantId()).lines().get(0);

        assertThat(progress.creditsCompleted()).as("the course is over and not passed").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line.enrollmentStatus()).isEqualTo("COMPLETED");
        assertThat(line.finalGrade()).isEqualTo("F");
        assertThat(directory.enrollmentsOf(s.student().studentId()).get(0).passed()).isFalse();
    }

    @Test
    void aSignedPassingGradeEarnsTheCredits() {
        var s = fixtures.provisionEnrolledScenario("RD8");
        complete(s, "B+");
        TestAuthentication.authenticateAs(s.student().userId());

        assertThat(records.degreeProgress(s.student().studentId(), s.tenant().tenantId()).creditsCompleted()).isEqualByComparingTo("4.0");
        assertThat(directory.enrollmentsOf(s.student().studentId()).get(0).passed()).isTrue();
    }

    @Test
    void aCompletedRowFromBeforeFinalGradesWereRecordedStillEarnsItsCredits() {
        var s = fixtures.provisionEnrolledScenario("RD9");
        jdbc.sql("UPDATE enrollment.enrollments SET status = 'COMPLETED' WHERE id = :id").param("id", s.enrollmentId()).update();
        TestAuthentication.authenticateAs(s.student().userId());

        assertThat(records.degreeProgress(s.student().studentId(), s.tenant().tenantId()).creditsCompleted()).isEqualByComparingTo("4.0");
    }

    /**
     * Regression: the record used to read each enrollment's section through the checked section read, which lets a student see a section only
     * while ENROLLED in it. The moment a course was COMPLETED, the student's own transcript and degree progress failed with 403, the very rows a
     * transcript exists to show. The record's single authorization gate is {@code assertRead}.
     */
    @Test
    void aStudentCanStillReadTheirOwnTranscriptOnceACourseIsCompleted() {
        var s = fixtures.provisionEnrolledScenario("RD6");
        fixtures.recordGrade(s, "A", new BigDecimal("4.0"));
        complete(s, "A");
        TestAuthentication.authenticateAs(s.student().userId());

        var transcript = records.transcript(s.student().studentId(), s.tenant().tenantId());

        assertThat(transcript.lines()).hasSize(1);
        assertThat(transcript.lines().get(0).enrollmentStatus()).isEqualTo("COMPLETED");
        assertThat(transcript.lines().get(0).finalGrade()).isEqualTo("A");
        assertThat(transcript.lines().get(0).courseCode()).isEqualTo("RD6C");
        assertThat(transcript.gpa()).isEqualByComparingTo("4.00");
    }
}
