package com.setu.learning.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.learning.domain.AssignmentStatus;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
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
 * Day 10 §5, §13: assignments. Note the authorization asymmetry versus
 * course_modules — COLLEGE_ADMIN is read-only here (see AssignmentService's
 * javadoc), so there is no "college admin can create" test to mirror
 * CourseModuleTests; only SUPER_ADMIN (via MANAGE_ASSIGNMENTS) or assigned
 * faculty can write.
 */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class AssignmentTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    AssignmentService assignmentService;

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
    void assignedFacultyCanCreateAssignment() {
        var s = setUp("AS1");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = assignmentService.create(s.sectionId, "Homework 1", new BigDecimal("100"),
            Instant.now().plus(7, ChronoUnit.DAYS), null);

        assertThat(result).isInstanceOf(AssignmentService.CreateResult.Success.class);
    }

    @Test
    void unassignedFacultyCannotCreateAssignment() {
        var s = setUp("AS2");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "AS2-OTHER");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> assignmentService.create(s.sectionId, "Homework 1", new BigDecimal("100"), null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void collegeAdminCannotCreateAssignmentButCanRead() {
        var s = setUp("AS3");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        assertThatThrownBy(() -> assignmentService.create(s.sectionId, "Homework 1", new BigDecimal("100"), null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invalidMaxMarksIsRejected() {
        var s = setUp("AS4");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = assignmentService.create(s.sectionId, "Homework 1", new BigDecimal("-5"), null, null);

        assertThat(result).isInstanceOf(AssignmentService.CreateResult.InvalidAssignmentData.class);
    }

    @Test
    void draftAssignmentHiddenFromStudentPublishedIsVisible() {
        var s = setUp("AS5");
        TestAuthentication.authenticateAs(s.faculty.userId());
        var created = (AssignmentService.CreateResult.Success)
            assignmentService.create(s.sectionId, "Homework 1", new BigDecimal("100"), null, null);
        TestAuthentication.clear();
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "AS5S");
        day9Fixtures.enrollStudent(student, s.sectionId);

        TestAuthentication.authenticateAs(student.userId());
        var draftView = assignmentService.get(created.assignmentId());
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.faculty.userId());
        assignmentService.publish(created.assignmentId());
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(student.userId());
        var publishedView = assignmentService.get(created.assignmentId());

        assertThat(draftView).isEmpty();
        assertThat(publishedView).isPresent();
        assertThat(publishedView.get().status()).isEqualTo(AssignmentStatus.PUBLISHED);
    }

    @Test
    void closeIsTerminalNoReopen() {
        var s = setUp("AS6");
        TestAuthentication.authenticateAs(s.faculty.userId());
        var created = (AssignmentService.CreateResult.Success)
            assignmentService.create(s.sectionId, "Homework 1", new BigDecimal("100"), null, null);
        assignmentService.publish(created.assignmentId());
        assignmentService.close(created.assignmentId());

        var result = assignmentService.publish(created.assignmentId());

        assertThat(result).isEqualTo(AssignmentService.TransitionResult.INVALID_TRANSITION);
    }

    @Test
    void gettingNonExistentAssignmentReturnsEmpty() {
        var s = setUp("AS7");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = assignmentService.get(UUID.randomUUID());

        assertThat(result).isEmpty();
    }
}
