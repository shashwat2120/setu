package com.setu.learning.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.learning.domain.CourseModuleStatus;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 10 §1, §13: course_modules CRUD and authorization matrix. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class CourseModuleTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    CourseModuleService courseModuleService;

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
    void assignedFacultyCanCreateModule() {
        var s = setUp("CM1");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = courseModuleService.create(s.sectionId, "Week 1", 1);

        assertThat(result).isInstanceOf(CourseModuleService.CreateResult.Success.class);
    }

    @Test
    void unassignedFacultyCannotCreateModule() {
        var s = setUp("CM2");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "CM2-OTHER");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> courseModuleService.create(s.sectionId, "Week 1", 1))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void facultyFromAnotherTenantCannotCreateModule() {
        var s = setUp("CM3A");
        var otherTenant = fixtures.provisionTenant("CM3B");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.FACULTY, "CM3B-F");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> courseModuleService.create(s.sectionId, "Week 1", 1))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void collegeAdminCanCreateModule() {
        var s = setUp("CM4");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var result = courseModuleService.create(s.sectionId, "Week 1", 1);

        assertThat(result).isInstanceOf(CourseModuleService.CreateResult.Success.class);
    }

    @Test
    void studentCannotCreateModule() {
        var s = setUp("CM5");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "CM5S");
        TestAuthentication.authenticateAs(student.userId());

        assertThatThrownBy(() -> courseModuleService.create(s.sectionId, "Week 1", 1))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void enrolledStudentCanReadModule() {
        var s = setUp("CM6");
        UUID moduleId = day10Create(s, "Week 1", 1);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "CM6S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = courseModuleService.get(moduleId);

        assertThat(result).isPresent();
    }

    @Test
    void unenrolledStudentCannotReadModule() {
        var s = setUp("CM7");
        UUID moduleId = day10Create(s, "Week 1", 1);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "CM7S");
        // never enrolled
        TestAuthentication.authenticateAs(student.userId());

        assertThatThrownBy(() -> courseModuleService.get(moduleId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void studentFromAnotherTenantCannotReadModule() {
        var s = setUp("CM8A");
        UUID moduleId = day10Create(s, "Week 1", 1);
        var otherTenant = fixtures.provisionTenant("CM8B");
        UUID otherDept = fixtures.provisionDepartment(otherTenant, "CM8BD");
        UUID otherProgram = fixtures.provisionProgram(otherTenant, otherDept, "CM8BP");
        UUID otherCurriculum = fixtures.provisionCurriculum(otherTenant, otherProgram, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(otherTenant, otherProgram, otherCurriculum, "CM8BS");
        TestAuthentication.authenticateAs(otherStudent.userId());

        assertThatThrownBy(() -> courseModuleService.get(moduleId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sequenceMustBeUniqueWithinSection() {
        var s = setUp("CM9");
        TestAuthentication.authenticateAs(s.faculty.userId());
        courseModuleService.create(s.sectionId, "Week 1", 1);

        var result = courseModuleService.create(s.sectionId, "Week 1 Again", 1);

        assertThat(result).isInstanceOf(CourseModuleService.CreateResult.SequenceAlreadyInUse.class);
    }

    @Test
    void gettingNonExistentModuleReturnsEmpty() {
        var s = setUp("CM10");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = courseModuleService.get(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void archivedModuleHiddenFromStudentButVisibleToFaculty() {
        var s = setUp("CM11");
        UUID moduleId = day10Create(s, "Week 1", 1);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "CM11S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        courseModuleService.setArchived(moduleId, true);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(student.userId());
        var studentView = courseModuleService.get(moduleId);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.faculty.userId());
        var facultyView = courseModuleService.get(moduleId);

        assertThat(studentView).isEmpty();
        assertThat(facultyView).isPresent();
        assertThat(facultyView.get().status()).isEqualTo(CourseModuleStatus.ARCHIVED);
    }

    private UUID day10Create(Scenario s, String title, int sequence) {
        TestAuthentication.authenticateAs(s.faculty.userId());
        try {
            return ((CourseModuleService.CreateResult.Success) courseModuleService.create(s.sectionId, title, sequence)).moduleId();
        } finally {
            TestAuthentication.clear();
        }
    }
}
