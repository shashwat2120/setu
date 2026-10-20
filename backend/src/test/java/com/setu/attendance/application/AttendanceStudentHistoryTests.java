package com.setu.attendance.application;

import com.setu.attendance.domain.CheckInLocation;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a student sees of their own check-ins, and who else may see them: the student themself, the section's faculty and an administrator, never another
 * student, and a guardian only without the place. Not {@code @Transactional}, for the reason in {@code AttendanceRecordTests}.
 */
@SpringBootTest
class AttendanceStudentHistoryTests {

    private static final double LAT = 19.0760;
    private static final double LON = 72.8777;

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    AttendanceSessionService attendanceSessionService;
    @Autowired
    AttendanceRecordService attendanceRecordService;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, Day9TestFixtures.Faculty faculty, Day8TestFixtures.Student student, UUID enrollmentId,
                             Day8TestFixtures.Student other) {
    }

    /** A student enrolled in a section who scanned from about 10 m away, and another student in the same tenant who did not. */
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
        UUID meetingId = day9Fixtures.provisionMeetingPattern(tenant, sectionId, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, sectionId);
        var other = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "O");
        day9Fixtures.enrollStudent(other, sectionId);

        TestAuthentication.authenticateAs(faculty.userId());
        var opened = (AttendanceSessionService.OpenResult.Success) attendanceSessionService.open(
            sectionId, meetingId, Instant.now().plus(1, ChronoUnit.HOURS), LAT, LON);
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(student.userId());
        attendanceRecordService.scan(opened.sessionId(), opened.rawToken(), new AttendanceRecordService.LocationReport(LAT + 0.0001, LON, 12.0));
        TestAuthentication.clear();
        return new Scenario(tenant, faculty, student, enrollmentId, other);
    }

    @Test
    void aStudentSeesTheirOwnCheckInAndWhereItWasMadeFrom() {
        var s = setUp("SHI1");
        TestAuthentication.authenticateAs(s.student.userId());

        var history = attendanceRecordService.recentForEnrollment(s.enrollmentId);

        assertThat(history.includeLocation()).isTrue();
        assertThat(history.records()).singleElement().satisfies(r -> {
            assertThat(r.status().name()).isEqualTo("PRESENT");
            assertThat(r.locationStatus()).isEqualTo(CheckInLocation.NEAR);
            assertThat(r.locationDistanceMeters()).isNotNull();
        });
    }

    @Test
    void theSectionsFacultyAndAnAdministratorSeeTheSameHistory() {
        var s = setUp("SHI2");

        TestAuthentication.authenticateAs(s.faculty.userId());
        assertThat(attendanceRecordService.recentForEnrollment(s.enrollmentId).records()).hasSize(1);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        var forAdmin = attendanceRecordService.recentForEnrollment(s.enrollmentId);
        assertThat(forAdmin.records()).hasSize(1);
        assertThat(forAdmin.includeLocation()).isTrue();
    }

    @Test
    void anotherStudentCannotReadSomeoneElsesHistory() {
        var s = setUp("SHI3");
        TestAuthentication.authenticateAs(s.other.userId());

        assertThatThrownBy(() -> attendanceRecordService.recentForEnrollment(s.enrollmentId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnknownEnrollmentHasNoHistory() {
        var s = setUp("SHI4");
        TestAuthentication.authenticateAs(s.student.userId());

        assertThat(attendanceRecordService.recentForEnrollment(UUID.randomUUID()).records()).isEmpty();
    }

    @Test
    void aFacultyMemberWhoLostTheRoleSeesNothingEvenIfStillAssigned() {
        var s = setUp("SHI5");
        TestAuthentication.authenticateAs(s.faculty.userId());
        assertThat(attendanceRecordService.recentForEnrollment(s.enrollmentId).records()).hasSize(1);
        TestAuthentication.clear();

        endFacultyRole(s.faculty.userId());
        TestAuthentication.authenticateAs(s.faculty.userId());

        assertThatThrownBy(() -> attendanceRecordService.recentForEnrollment(s.enrollmentId)).isInstanceOf(AccessDeniedException.class);
    }

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private void endFacultyRole(UUID userId) {
        jdbc.sql("update identity.user_roles set ends_at = now() - interval '2 seconds' where user_id = ? and ends_at is null").param(userId).update();
    }
}
