package com.setu.attendance.application;

import com.setu.enrollment.application.EnrollmentService;
import com.setu.identity.TestAuthentication;
import com.setu.learning.application.CourseModuleService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The faculty rule, decided and asserted: a section assignment says WHICH section a faculty member works in, never that they may act as faculty. A
 * member who is still assigned but whose faculty role has been taken away reaches nothing in that section, in any module. Assessment always worked this
 * way (its own permission); Attendance, Learning and the section and enrollment lists used to let an assignment alone through
 * ({@code AuthorizationContext#holdsFacultyRole} is the one check they all ask now).
 *
 * <p>Not {@code @Transactional}, for the reason the other attendance tests give: closing a session uses a separate connection.
 */
@SpringBootTest
class FacultyRoleRequiredTests {

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
    @Autowired
    CourseModuleService courseModuleService;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    AuthorizationContext authorizationContext;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID sectionId, UUID meetingId, Day9TestFixtures.Faculty faculty) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        UUID meetingId = day9Fixtures.provisionMeetingPattern(tenant, sectionId, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        return new Scenario(tenant, sectionId, meetingId, faculty);
    }

    /** The admin takes the faculty role away; the section assignment is untouched. */
    private void takeFacultyRoleAway(UUID userId) {
        int ended = jdbc.sql("update identity.user_roles set ends_at = now() - interval '2 seconds' where user_id = ? and ends_at is null")
            .param(userId).update();
        assertThat(ended).as("the faculty role grant was ended").isEqualTo(1);
    }

    @Test
    void anAssignedFacultyMemberWhoStillHoldsTheRoleWorksTheSection() {
        var s = setUp("FRR1");
        TestAuthentication.authenticateAs(s.faculty.userId());

        assertThat(authorizationContext.holdsFacultyRole(s.tenant.tenantId())).isTrue();
        assertThat(attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS)))
            .isInstanceOf(AttendanceSessionService.OpenResult.Success.class);
        assertThatCode(() -> courseModuleService.listForSection(s.sectionId, s.tenant.tenantId())).doesNotThrowAnyException();
        assertThatCode(() -> enrollmentService.listForSection(s.sectionId, s.tenant.tenantId(), Pageable.unpaged())).doesNotThrowAnyException();
    }

    @Test
    void anAssignmentThatOutlivedTheRoleOpensNoAttendance() {
        var s = setUp("FRR2");
        takeFacultyRoleAway(s.faculty.userId());
        TestAuthentication.authenticateAs(s.faculty.userId());

        assertThat(authorizationContext.holdsFacultyRole(s.tenant.tenantId())).isFalse();
        assertThatThrownBy(() -> attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAssignmentThatOutlivedTheRoleReadsNoAttendanceRecords() {
        var s = setUp("FRR3");
        TestAuthentication.authenticateAs(s.faculty.userId());
        var opened = (AttendanceSessionService.OpenResult.Success)
            attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThatCode(() -> attendanceRecordService.listForSession(opened.sessionId(), s.tenant.tenantId())).doesNotThrowAnyException();
        TestAuthentication.clear();

        takeFacultyRoleAway(s.faculty.userId());
        TestAuthentication.authenticateAs(s.faculty.userId());

        assertThatThrownBy(() -> attendanceRecordService.listForSession(opened.sessionId(), s.tenant.tenantId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAssignmentThatOutlivedTheRoleOpensNoLearningContent() {
        var s = setUp("FRR4");
        takeFacultyRoleAway(s.faculty.userId());
        TestAuthentication.authenticateAs(s.faculty.userId());

        assertThatThrownBy(() -> courseModuleService.listForSection(s.sectionId, s.tenant.tenantId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> courseModuleService.create(s.sectionId, "Week 1", 1))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAssignmentThatOutlivedTheRoleListsNoEnrollments() {
        var s = setUp("FRR5");
        takeFacultyRoleAway(s.faculty.userId());
        TestAuthentication.authenticateAs(s.faculty.userId());

        assertThatThrownBy(() -> enrollmentService.listForSection(s.sectionId, s.tenant.tenantId(), Pageable.unpaged()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdministratorStillReachesTheSectionWithoutAnAssignmentOrTheFacultyRole() {
        var s = setUp("FRR6");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        assertThat(authorizationContext.holdsFacultyRole(s.tenant.tenantId())).isFalse();
        assertThat(attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS)))
            .isInstanceOf(AttendanceSessionService.OpenResult.Success.class);
        assertThatCode(() -> enrollmentService.listForSection(s.sectionId, s.tenant.tenantId(), Pageable.unpaged())).doesNotThrowAnyException();
    }
}
