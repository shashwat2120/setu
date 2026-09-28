package com.setu.assessment.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.assessment.domain.AssessmentStatus;
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
 * Day 11 §Assessment, §Authorization: assessments lifecycle
 * (docs/state-machines.md #17) and the corrected assignment-defines-scope,
 * permission-defines-action authorization idiom (see
 * AssessmentAccessSupport's javadoc).
 * <p>
 * Deliberately NOT {@code @Transactional} — Sprint 5D gave {@code
 * AssessmentComponentService#create} a {@code REQUIRES_NEW} insert guard
 * (see its javadoc), so a test transaction's own uncommitted assessment row
 * would be invisible to that guard's separate connection, the same lesson
 * already applied to every other test class in this package that touches a
 * REQUIRES_NEW code path.
 */
@SpringBootTest
class AssessmentTests {

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
    void assignedFacultyCanCreateAssessment() {
        var s = setUp("AT1");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = assessmentService.create(s.sectionId, "QUIZ", "Quiz 1", new BigDecimal("10"), 30, null, null);

        assertThat(result).isInstanceOf(AssessmentService.CreateResult.Success.class);
    }

    @Test
    void unassignedFacultyCannotCreateAssessment() {
        var s = setUp("AT2");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "AT2-OTHER");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> assessmentService.create(s.sectionId, "QUIZ", "Quiz 1", new BigDecimal("10"), 30, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void facultyFromAnotherTenantCannotCreateAssessment() {
        var s = setUp("AT3A");
        var otherTenant = fixtures.provisionTenant("AT3B");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.FACULTY, "AT3B-F");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> assessmentService.create(s.sectionId, "QUIZ", "Quiz 1", new BigDecimal("10"), 30, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void collegeAdminCanCreateAssessmentViaExplicitPermission() {
        var s = setUp("AT4");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var result = assessmentService.create(s.sectionId, "QUIZ", "Quiz 1", new BigDecimal("10"), 30, null, null);

        assertThat(result).isInstanceOf(AssessmentService.CreateResult.Success.class);
    }

    @Test
    void studentCannotCreateAssessment() {
        var s = setUp("AT5");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "AT5S");
        TestAuthentication.authenticateAs(student.userId());

        assertThatThrownBy(() -> assessmentService.create(s.sectionId, "QUIZ", "Quiz 1", new BigDecimal("10"), 30, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void scheduleRequiresComponentWeightsSumTo100() {
        var s = setUp("AT6");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz", null, null);
        TestAuthentication.authenticateAs(s.faculty.userId());
        assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("40"), 1);

        var result = assessmentService.schedule(assessmentId);

        assertThat(result).isInstanceOf(AssessmentService.TransitionResult.ComponentWeightsInvalid.class);
    }

    @Test
    void scheduleSucceedsWhenWeightsSumTo100() {
        var s = setUp("AT7");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz", null, null);
        TestAuthentication.authenticateAs(s.faculty.userId());
        assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("60"), 1);
        assessmentComponentService.create(assessmentId, "C2", new BigDecimal("10"), new BigDecimal("40"), 2);

        var result = assessmentService.schedule(assessmentId);

        assertThat(result).isInstanceOf(AssessmentService.TransitionResult.Success.class);
    }

    @Test
    void fullLifecycleDraftToPublished() {
        var s = setUp("AT8");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz",
            Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
        TestAuthentication.authenticateAs(s.faculty.userId());
        assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("100"), 1);

        assertThat(assessmentService.schedule(assessmentId)).isInstanceOf(AssessmentService.TransitionResult.Success.class);
        assertThat(assessmentService.open(assessmentId)).isInstanceOf(AssessmentService.TransitionResult.Success.class);
        assertThat(assessmentService.close(assessmentId)).isInstanceOf(AssessmentService.TransitionResult.Success.class);
        assertThat(assessmentService.publish(assessmentId)).isInstanceOf(AssessmentService.TransitionResult.Success.class);

        var result = assessmentService.get(assessmentId);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(AssessmentStatus.PUBLISHED);
    }

    @Test
    void publishedIsTerminalNoFurtherTransition() {
        var s = setUp("AT9");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz",
            Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
        TestAuthentication.authenticateAs(s.faculty.userId());
        assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("100"), 1);
        assessmentService.schedule(assessmentId);
        assessmentService.open(assessmentId);
        assessmentService.close(assessmentId);
        assessmentService.publish(assessmentId);

        var result = assessmentService.voidAssessment(assessmentId);

        assertThat(result).isInstanceOf(AssessmentService.TransitionResult.InvalidTransition.class);
    }

    @Test
    void draftAssessmentHiddenFromEnrolledStudentOpenIsVisible() {
        var s = setUp("AT10");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(s.faculty.userId(), s.sectionId, "Quiz",
            Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "AT10S");
        day9Fixtures.enrollStudent(student, s.sectionId);

        TestAuthentication.authenticateAs(student.userId());
        var draftView = assessmentService.get(assessmentId);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.faculty.userId());
        assessmentComponentService.create(assessmentId, "C1", new BigDecimal("10"), new BigDecimal("100"), 1);
        assessmentService.schedule(assessmentId);
        assessmentService.open(assessmentId);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(student.userId());
        var openView = assessmentService.get(assessmentId);

        assertThat(draftView).isEmpty();
        assertThat(openView).isPresent();
    }

    @Test
    void gettingNonExistentAssessmentReturnsEmpty() {
        var s = setUp("AT11");
        TestAuthentication.authenticateAs(s.faculty.userId());

        assertThat(assessmentService.get(UUID.randomUUID())).isEmpty();
    }
}
