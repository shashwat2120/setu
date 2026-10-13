package com.setu.attendance.application;

import com.setu.attendance.domain.AttendanceSessionStatus;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
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
 * Day 9 §12-13 attendance session state-machine and authorization test
 * matrix. Deliberately NOT {@code @Transactional} (unlike most of this
 * project's test classes): {@code close()} uses
 * {@code AttendanceRecordInsertGuard}'s {@code PROPAGATION_REQUIRES_NEW}
 * insert, which opens a genuinely separate DB connection — one that cannot
 * see this test's own setup data if that data were only sitting in an
 * uncommitted, auto-rollback-at-the-end test transaction. Each test's
 * fixtures use fresh random ids, so accumulating committed rows across
 * runs doesn't affect correctness (matches the established
 * {@code EnrollmentConcurrencyTests}/{@code Day7ConcurrencyTests}
 * precedent for the same reason).
 */
@SpringBootTest
class AttendanceSessionTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    AttendanceSessionService attendanceSessionService;

    @AfterEach
    void clearSecurityContext() {
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

    @Test
    void assignedFacultyCanOpenSession() {
        var s = setUp("ATT1");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(result).isInstanceOf(AttendanceSessionService.OpenResult.Success.class);
    }

    @Test
    void unassignedFacultyIsDenied() {
        var s = setUp("ATT2");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "ATT2-OTHER");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void facultyFromAnotherTenantIsDenied() {
        var s = setUp("ATT3A");
        var otherTenant = fixtures.provisionTenant("ATT3B");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.FACULTY, "ATT3B-F");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void studentCannotStartAttendance() {
        var s = setUp("ATT4");
        fixtures.provisionTenant("ATT4S");
        UUID studentUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.STUDENT, "ATT4-STU");
        TestAuthentication.authenticateAs(studentUserId);

        assertThatThrownBy(() -> attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void guardianCannotStartAttendance() {
        var s = setUp("ATT5");
        UUID guardianUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.GUARDIAN, "ATT5-GRD");
        TestAuthentication.authenticateAs(guardianUserId);

        assertThatThrownBy(() -> attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void collegeAdminCanOpenAndCloseSession() {
        var s = setUp("ATT6");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        UUID sessionId = ((AttendanceSessionService.OpenResult.Success)
            attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS))).sessionId();

        var closeResult = attendanceSessionService.close(sessionId);

        assertThat(closeResult).isInstanceOf(AttendanceSessionService.CloseResult.Success.class);
    }

    @Test
    void duplicateOpenForSameMeetingIsRejected() {
        var s = setUp("ATT7");
        TestAuthentication.authenticateAs(s.faculty.userId());
        attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));

        var result = attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(result).isInstanceOf(AttendanceSessionService.OpenResult.SessionAlreadyOpenForMeeting.class);
    }

    @Test
    void meetingNotBelongingToSectionIsRejected() {
        var s = setUp("ATT8A");
        var s2 = setUp("ATT8B");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = attendanceSessionService.open(s.sectionId, s2.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(result).isInstanceOf(AttendanceSessionService.OpenResult.MeetingSectionMismatch.class);
    }

    @Test
    void closeIsTerminalNoReopen() {
        var s = setUp("ATT9");
        TestAuthentication.authenticateAs(s.faculty.userId());
        UUID sessionId = ((AttendanceSessionService.OpenResult.Success)
            attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS))).sessionId();
        attendanceSessionService.close(sessionId);

        var result = attendanceSessionService.close(sessionId);

        assertThat(result).isInstanceOf(AttendanceSessionService.CloseResult.InvalidTransition.class);
        assertThat(attendanceSessionService.get(sessionId).orElseThrow().status()).isEqualTo(AttendanceSessionStatus.CLOSED);
    }

    @Test
    void rotateQrOnClosedSessionIsRejected() {
        var s = setUp("ATT10");
        TestAuthentication.authenticateAs(s.faculty.userId());
        UUID sessionId = ((AttendanceSessionService.OpenResult.Success)
            attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS))).sessionId();
        attendanceSessionService.close(sessionId);

        var result = attendanceSessionService.rotateQr(sessionId);

        assertThat(result).isInstanceOf(AttendanceSessionService.RotateResult.SessionClosed.class);
    }

    // --- Day 16 §college-admin: listForSection ---

    @Test
    void listForSection_returnsSessionsNewestFirst_forAssignedFacultyOrAdmin() {
        var s = setUp("ATT11");
        TestAuthentication.authenticateAs(s.faculty.userId());
        UUID first = ((AttendanceSessionService.OpenResult.Success)
            attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS))).sessionId();
        attendanceSessionService.close(first);
        UUID second = ((AttendanceSessionService.OpenResult.Success)
            attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS))).sessionId();

        var sessions = attendanceSessionService.listForSection(s.sectionId);

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).id()).isEqualTo(second); // newest first
        assertThat(sessions.get(1).id()).isEqualTo(first);
    }

    @Test
    void listForSection_unassignedFacultyIsDenied() {
        var s = setUp("ATT12");
        TestAuthentication.authenticateAs(s.faculty.userId());
        attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));
        TestAuthentication.clear();
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "ATT12-OTHER");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> attendanceSessionService.listForSection(s.sectionId))
            .isInstanceOf(AccessDeniedException.class);
    }
}
