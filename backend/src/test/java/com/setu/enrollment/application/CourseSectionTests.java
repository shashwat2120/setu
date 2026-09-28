package com.setu.enrollment.application;

import com.setu.directory.application.FacultyProfileService;
import com.setu.enrollment.domain.CourseSectionStatus;
import com.setu.enrollment.domain.FacultyAssignmentRole;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.shared.identity.PersonDirectory;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 7 §16 SECTIONS test matrix. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class CourseSectionTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    CourseSectionService courseSectionService;
    @Autowired
    FacultyAssignmentService facultyAssignmentService;
    @Autowired
    FacultyProfileService facultyProfileService;
    @Autowired
    PersonDirectory personDirectory;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Fixture(Day7TestFixtures.Tenant tenant, UUID courseId, UUID periodId, UUID roomId) {
    }

    private Fixture setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = fixtures.provisionPeriod(tenant, prefix + "P");
        UUID buildingId = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID roomId = fixtures.provisionRoom(tenant, buildingId, "101", 40);
        return new Fixture(tenant, courseId, periodId, roomId);
    }

    private record FacultyWithUser(UUID facultyId, UUID userId) {
    }

    private FacultyWithUser provisionFacultyWithUser(Day7TestFixtures.Tenant tenant, String prefix) {
        UUID facultyUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.FACULTY, prefix);
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            UUID personId = personDirectory.createPerson(facultyUserId, prefix + " Legal Name", null, null, null,
                prefix + "@example.com", null, null);
            var result = (FacultyProfileService.CreateResult.Success)
                facultyProfileService.create(tenant.tenantId(), personId, prefix + "-EMP", "Lecturer", LocalDate.of(2020, 1, 1));
            return new FacultyWithUser(result.facultyId(), facultyUserId);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void validSectionCreation() {
        var f = setUp("SEC1");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = courseSectionService.create(f.courseId, f.periodId, null, "S1", 30, f.roomId, null, null);

        assertThat(result).isInstanceOf(CourseSectionService.CreateResult.Success.class);
    }

    @Test
    void invalidCapacityIsRejected() {
        var f = setUp("SEC2");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = courseSectionService.create(f.courseId, f.periodId, null, "S1", 0, f.roomId, null, null);

        assertThat(result).isInstanceOf(CourseSectionService.CreateResult.InvalidSectionData.class);
    }

    @Test
    void invalidRegistrationWindowIsRejected() {
        var f = setUp("SEC3");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        Instant open = Instant.now();
        Instant close = open.minus(1, ChronoUnit.DAYS);

        var result = courseSectionService.create(f.courseId, f.periodId, null, "S1", 30, f.roomId, open, close);

        assertThat(result).isInstanceOf(CourseSectionService.CreateResult.InvalidSectionData.class);
    }

    @Test
    void wrongTenantCourseIsRejected() {
        var f = setUp("SEC4A");
        var otherTenant = fixtures.provisionTenant("SEC4B");
        UUID otherDept = fixtures.provisionDepartment(otherTenant, "SEC4BD");
        UUID otherCourse = fixtures.provisionCourse(otherTenant, otherDept, "SEC4BC");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        // The section's course determines the authorized tenant — admin of
        // f.tenant has no role in the course's real tenant (otherTenant), so
        // this is denied at the authorization layer before any tenant-match
        // comparison even runs, exactly like the analogous ProgramService
        // cross-tenant-department case.
        assertThatThrownBy(() -> courseSectionService.create(otherCourse, f.periodId, null, "S1", 30, f.roomId, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void wrongTenantPeriodIsRejected() {
        var f = setUp("SEC5A");
        var otherTenant = fixtures.provisionTenant("SEC5B");
        UUID otherPeriod = fixtures.provisionPeriod(otherTenant, "SEC5BP");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = courseSectionService.create(f.courseId, otherPeriod, null, "S1", 30, f.roomId, null, null);

        assertThat(result).isInstanceOf(CourseSectionService.CreateResult.TenantMismatch.class);
    }

    @Test
    void wrongTenantRoomIsRejected() {
        var f = setUp("SEC6A");
        var otherTenant = fixtures.provisionTenant("SEC6B");
        UUID otherBuilding = fixtures.provisionBuilding(otherTenant, "SEC6BB");
        UUID otherRoom = fixtures.provisionRoom(otherTenant, otherBuilding, "999", 10);
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = courseSectionService.create(f.courseId, f.periodId, null, "S1", 30, otherRoom, null, null);

        assertThat(result).isInstanceOf(CourseSectionService.CreateResult.TenantMismatch.class);
    }

    @Test
    void roomCapacityInsufficientIsRejected() {
        var f = setUp("SEC7");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = courseSectionService.create(f.courseId, f.periodId, null, "S1", 100, f.roomId, null, null);

        assertThat(result).isInstanceOf(CourseSectionService.CreateResult.RoomCapacityInsufficient.class);
    }

    @Test
    void optionalClassAndRoomRemainOptional() {
        var f = setUp("SEC8");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = courseSectionService.create(f.courseId, f.periodId, null, "S1", 30, null, null, null);

        assertThat(result).isInstanceOf(CourseSectionService.CreateResult.Success.class);
        UUID sectionId = ((CourseSectionService.CreateResult.Success) result).sectionId();
        var section = courseSectionService.get(sectionId).orElseThrow();
        assertThat(section.classId()).isNull();
        assertThat(section.roomId()).isNull();
    }

    @Test
    void lifecycleTransitionsFollowStateMachine() {
        var f = setUp("SEC9");
        UUID sectionId = fixtures.provisionSection(f.tenant, f.courseId, f.periodId, "S1", 30, f.roomId);
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        assertThat(courseSectionService.changeStatus(sectionId, CourseSectionStatus.OPEN)).isEqualTo(CourseSectionService.StatusChangeResult.SUCCESS);
        assertThat(courseSectionService.changeStatus(sectionId, CourseSectionStatus.CLOSED)).isEqualTo(CourseSectionService.StatusChangeResult.SUCCESS);
        // CLOSED -> OPEN allowed as an audited correction
        assertThat(courseSectionService.changeStatus(sectionId, CourseSectionStatus.OPEN)).isEqualTo(CourseSectionService.StatusChangeResult.SUCCESS);
        assertThat(courseSectionService.changeStatus(sectionId, CourseSectionStatus.CANCELLED)).isEqualTo(CourseSectionService.StatusChangeResult.SUCCESS);
        // CANCELLED is terminal
        assertThat(courseSectionService.changeStatus(sectionId, CourseSectionStatus.OPEN)).isEqualTo(CourseSectionService.StatusChangeResult.INVALID_TRANSITION);
    }

    // --- Day 16 §faculty: assigned-faculty view bypass ---

    @Test
    void assignedFacultyCanViewTheirOwnSection() {
        var f = setUp("SEC10");
        UUID sectionId = fixtures.provisionSection(f.tenant, f.courseId, f.periodId, "S1", 30, f.roomId);
        var faculty = provisionFacultyWithUser(f.tenant, "SEC10F");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        facultyAssignmentService.assign(sectionId, faculty.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(faculty.userId());
        var section = courseSectionService.get(sectionId);

        assertThat(section).isPresent();
        assertThat(section.get().id()).isEqualTo(sectionId);
    }

    @Test
    void unassignedFacultyCannotViewAnotherFacultysSection() {
        var f = setUp("SEC11");
        UUID sectionId = fixtures.provisionSection(f.tenant, f.courseId, f.periodId, "S1", 30, f.roomId);
        var facultyA = provisionFacultyWithUser(f.tenant, "SEC11FA");
        var facultyB = provisionFacultyWithUser(f.tenant, "SEC11FB");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        facultyAssignmentService.assign(sectionId, facultyA.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(facultyB.userId());
        assertThatThrownBy(() -> courseSectionService.get(sectionId))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §student: a student actively ENROLLED in this exact section can read its basic info ---

    @Test
    void enrolledStudentCanReadTheirOwnEnrolledSection() {
        var tenant = fixtures.provisionTenant("SEC12");
        UUID dept = fixtures.provisionDepartment(tenant, "SEC12D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "SEC12P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, "SEC12C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, "SEC12PR");
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, "SEC12S");
        day9Fixtures.enrollStudent(student, sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = courseSectionService.get(sectionId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(sectionId);
    }

    @Test
    void unenrolledStudentInTheSameTenantCannotReadAnotherStudentsSection() {
        var tenant = fixtures.provisionTenant("SEC13");
        UUID dept = fixtures.provisionDepartment(tenant, "SEC13D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "SEC13P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, "SEC13C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, "SEC13PR");
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, null);
        UUID unenrolledStudentUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.STUDENT, "SEC13S");
        TestAuthentication.authenticateAs(unenrolledStudentUserId);

        assertThatThrownBy(() -> courseSectionService.get(sectionId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void userWithNoRoleInTheTenantCannotReadTheSection() {
        var f = setUp("SEC14A");
        UUID sectionId = fixtures.provisionSection(f.tenant, f.courseId, f.periodId, "S1", 30, f.roomId);
        var otherTenant = fixtures.provisionTenant("SEC14B");
        UUID outsiderUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.STUDENT, "SEC14BS");
        TestAuthentication.authenticateAs(outsiderUserId);

        assertThatThrownBy(() -> courseSectionService.get(sectionId))
            .isInstanceOf(AccessDeniedException.class);
    }
}
