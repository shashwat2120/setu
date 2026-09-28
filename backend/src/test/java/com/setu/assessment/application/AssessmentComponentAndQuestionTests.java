package com.setu.assessment.application;

import com.setu.identity.TestAuthentication;
import com.setu.assessment.domain.QuestionType;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import com.setu.testsupport.Day11TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 11 §Components, §Questions: assessment_components/questions —
 * DRAFT-only mutation and metadata redaction.
 * <p>
 * Deliberately NOT {@code @Transactional} — Sprint 5D gave {@code
 * AssessmentComponentService#create} a {@code REQUIRES_NEW} insert guard
 * (see its javadoc), so a test transaction's own uncommitted assessment row
 * would be invisible to that guard's separate connection, the same lesson
 * already applied to every other test class in this package that touches a
 * REQUIRES_NEW code path.
 */
@SpringBootTest
class AssessmentComponentAndQuestionTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day11TestFixtures day11Fixtures;
    @Autowired
    AssessmentService assessmentService;
    @Autowired
    AssessmentComponentService assessmentComponentService;
    @Autowired
    QuestionService questionService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId,
                             Day9TestFixtures.Faculty faculty) {
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
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        return new Scenario(tenant, programId, curriculumId, sectionId, faculty);
    }

    @Test
    void componentCodeMustBeUniqueWithinAssessment() {
        var s = setUp("AQ1");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz", null, null);
        TestAuthentication.authenticateAs(s.faculty.userId());
        assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("100"), 1);

        var result = assessmentComponentService.create(assessmentId, "C1", new BigDecimal("5"), new BigDecimal("50"), 2);

        assertThat(result).isInstanceOf(AssessmentComponentService.CreateResult.CodeAlreadyInUse.class);
    }

    @Test
    void cannotAddComponentOnceScheduled() {
        var s = setUp("AQ2");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz", null, null);
        TestAuthentication.authenticateAs(s.faculty.userId());
        assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("100"), 1);
        assessmentService.schedule(assessmentId);

        var result = assessmentComponentService.create(assessmentId, "C2", new BigDecimal("5"), new BigDecimal("50"), 2);

        assertThat(result).isInstanceOf(AssessmentComponentService.CreateResult.NotDraft.class);
    }

    @Test
    void questionRequiresPositivePoints() {
        var s = setUp("AQ3");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz", null, null);
        TestAuthentication.authenticateAs(s.faculty.userId());
        var component = (AssessmentComponentService.CreateResult.Success)
            assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("100"), 1);

        var result = questionService.create(component.componentId(), QuestionType.MCQ_SINGLE, "2+2?", BigDecimal.ZERO,
            null, null, null);

        assertThat(result).isInstanceOf(QuestionService.CreateResult.InvalidQuestionData.class);
    }

    @Test
    void enrolledStudentNeverSeesMetadataJson() {
        var s = setUp("AQ4");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "AQ4S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var questions = questionService.listForComponent(open.componentId());

        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).metadataJson()).isNull();
    }

    @Test
    void assignedFacultySeesMetadataJson() {
        var s = setUp("AQ5");
        var open = day11Fixtures.provisionOpenAssessment(s.faculty.userId(), s.sectionId, "Quiz");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var questions = questionService.listForComponent(open.componentId());

        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).metadataJson()).isNotNull();
    }

    @Test
    void studentCannotCreateQuestion() {
        var s = setUp("AQ6");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz", null, null);
        TestAuthentication.authenticateAs(s.faculty.userId());
        var component = (AssessmentComponentService.CreateResult.Success)
            assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("100"), 1);
        TestAuthentication.clear();
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "AQ6S");
        TestAuthentication.authenticateAs(student.userId());

        assertThatThrownBy(() -> questionService.create(component.componentId(), QuestionType.ESSAY, "Discuss.",
            new BigDecimal("5"), null, null, null)).isInstanceOf(AccessDeniedException.class);
    }
}
