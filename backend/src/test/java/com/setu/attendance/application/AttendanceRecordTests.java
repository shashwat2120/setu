package com.setu.attendance.application;

import com.setu.attendance.domain.AttendanceRecordStatus;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day10TestFixtures;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 9 §4-6, §10 student scan flow and manual correction test matrix.
 * Deliberately NOT {@code @Transactional} — see
 * {@code AttendanceSessionTests}'s javadoc for why: {@code scan()}/
 * {@code close()} use {@code AttendanceRecordInsertGuard}'s
 * {@code PROPAGATION_REQUIRES_NEW} insert, which needs this test's own
 * setup data to be genuinely committed, not sitting in an uncommitted
 * auto-rollback transaction.
 */
@SpringBootTest
class AttendanceRecordTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    AttendanceSessionService attendanceSessionService;
    @Autowired
    AttendanceRecordService attendanceRecordService;
    @Autowired
    GuardianAccessService guardianAccessService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId,
                             UUID meetingId, Day9TestFixtures.Faculty faculty) {
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
        UUID meetingId = day9Fixtures.provisionMeetingPattern(tenant, sectionId, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        return new Scenario(tenant, programId, curriculumId, sectionId, meetingId, faculty);
    }

    private record OpenedSession(String rawToken, UUID sessionId) {
    }

    private OpenedSession openSession(Scenario s) {
        TestAuthentication.authenticateAs(s.faculty.userId());
        try {
            var result = (AttendanceSessionService.OpenResult.Success)
                attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));
            return new OpenedSession(result.rawToken(), result.sessionId());
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void validScanMarksPresent() {
        var s = setUp("REC1");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC1S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = attendanceRecordService.scan(session.sessionId(), session.rawToken());

        assertThat(result).isInstanceOf(AttendanceRecordService.ScanResult.Success.class);
    }

    @Test
    void duplicateScanIsIdempotent() {
        var s = setUp("REC2");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC2S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID recordId = ((AttendanceRecordService.ScanResult.Success) attendanceRecordService.scan(session.sessionId(), session.rawToken())).recordId();

        var second = attendanceRecordService.scan(session.sessionId(), session.rawToken());

        assertThat(second).isInstanceOf(AttendanceRecordService.ScanResult.AlreadyRecorded.class);
        assertThat(((AttendanceRecordService.ScanResult.AlreadyRecorded) second).recordId()).isEqualTo(recordId);
    }

    @Test
    void invalidTokenIsRejected() {
        var s = setUp("REC3");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = attendanceRecordService.scan(session.sessionId(), "not-the-real-token");

        assertThat(result).isInstanceOf(AttendanceRecordService.ScanResult.InvalidToken.class);
    }

    @Test
    void rotatedTokenInvalidatesThePreviousOne() {
        var s = setUp("REC4");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC4S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        attendanceSessionService.rotateQr(session.sessionId());
        TestAuthentication.authenticateAs(student.userId());

        var result = attendanceRecordService.scan(session.sessionId(), session.rawToken());

        assertThat(result).isInstanceOf(AttendanceRecordService.ScanResult.InvalidToken.class);
    }

    @Test
    void closedSessionRejectsScan() {
        var s = setUp("REC5");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC5S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(s.faculty.userId());
        attendanceSessionService.close(session.sessionId());
        TestAuthentication.authenticateAs(student.userId());

        var result = attendanceRecordService.scan(session.sessionId(), session.rawToken());

        assertThat(result).isInstanceOf(AttendanceRecordService.ScanResult.SessionNotOpen.class);
    }

    @Test
    void notEnrolledStudentIsRejected() {
        var s = setUp("REC6");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC6S");
        // never enrolled in the section
        TestAuthentication.authenticateAs(student.userId());

        var result = attendanceRecordService.scan(session.sessionId(), session.rawToken());

        assertThat(result).isInstanceOf(AttendanceRecordService.ScanResult.NotEnrolled.class);
    }

    @Test
    void crossTenantStudentIsRejected() {
        var s = setUp("REC7A");
        var session = openSession(s);
        var otherTenant = fixtures.provisionTenant("REC7B");
        UUID otherDept = fixtures.provisionDepartment(otherTenant, "REC7BD");
        UUID otherProgram = fixtures.provisionProgram(otherTenant, otherDept, "REC7BP");
        UUID otherCurriculum = fixtures.provisionCurriculum(otherTenant, otherProgram, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(otherTenant, otherProgram, otherCurriculum, "REC7BS");
        TestAuthentication.authenticateAs(otherStudent.userId());

        var result = attendanceRecordService.scan(session.sessionId(), session.rawToken());

        assertThat(result).isInstanceOf(AttendanceRecordService.ScanResult.StudentNotFound.class);
    }

    @Test
    void sessionCloseGeneratesAbsentForUnscannedStudents() {
        var s = setUp("REC8");
        var session = openSession(s);
        var scanned = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC8SA");
        var unscanned = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC8SB");
        day9Fixtures.enrollStudent(scanned, s.sectionId);
        UUID unscannedEnrollmentId = day9Fixtures.enrollStudent(unscanned, s.sectionId);
        TestAuthentication.authenticateAs(scanned.userId());
        attendanceRecordService.scan(session.sessionId(), session.rawToken());
        TestAuthentication.authenticateAs(s.faculty.userId());

        var closeResult = attendanceSessionService.close(session.sessionId());

        assertThat(closeResult).isInstanceOf(AttendanceSessionService.CloseResult.Success.class);
        assertThat(((AttendanceSessionService.CloseResult.Success) closeResult).absentRecordsGenerated()).isEqualTo(1);
        var records = attendanceRecordService.listForSession(session.sessionId(), s.tenant.tenantId());
        assertThat(records).anyMatch(r -> r.enrollmentId().equals(unscannedEnrollmentId) && r.status() == AttendanceRecordStatus.ABSENT);
    }

    @Test
    void correctionRequiresSessionClosed() {
        var s = setUp("REC9");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC9S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID recordId = ((AttendanceRecordService.ScanResult.Success) attendanceRecordService.scan(session.sessionId(), session.rawToken())).recordId();
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = attendanceRecordService.correct(recordId, AttendanceRecordStatus.LATE, "still open");

        assertThat(result).isInstanceOf(AttendanceRecordService.CorrectResult.SessionStillOpen.class);
    }

    @Test
    void assignedFacultyCanCorrectAfterClose() {
        var s = setUp("REC10");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC10S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID recordId = ((AttendanceRecordService.ScanResult.Success) attendanceRecordService.scan(session.sessionId(), session.rawToken())).recordId();
        TestAuthentication.authenticateAs(s.faculty.userId());
        attendanceSessionService.close(session.sessionId());

        var result = attendanceRecordService.correct(recordId, AttendanceRecordStatus.LATE, "walked in late");

        assertThat(result).isInstanceOf(AttendanceRecordService.CorrectResult.Success.class);
    }

    @Test
    void unauthorizedFacultyCannotCorrectAnotherSectionsRecord() {
        var s = setUp("REC11");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC11S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        UUID recordId = ((AttendanceRecordService.ScanResult.Success) attendanceRecordService.scan(session.sessionId(), session.rawToken())).recordId();
        TestAuthentication.authenticateAs(s.faculty.userId());
        attendanceSessionService.close(session.sessionId());
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), com.setu.identity.domain.RoleCode.FACULTY, "REC11-OTHER");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> attendanceRecordService.correct(recordId, AttendanceRecordStatus.LATE, "not my section"))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §student: percentageForEnrollment IDOR fix ---

    @Test
    void studentCanViewTheirOwnAttendancePercentage() {
        var s = setUp("REC12");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC12S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        attendanceRecordService.scan(session.sessionId(), session.rawToken());

        var pct = attendanceRecordService.percentageForEnrollment(enrollmentId);

        assertThat(pct.total()).isEqualTo(1);
        assertThat(pct.presentOrLate()).isEqualTo(1);
    }

    @Test
    void unrelatedStudentCannotViewAnotherStudentsAttendancePercentage() {
        var s = setUp("REC13");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC13S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        attendanceRecordService.scan(session.sessionId(), session.rawToken());
        UUID otherStudentUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), com.setu.identity.domain.RoleCode.STUDENT, "REC13-OTHER");
        TestAuthentication.authenticateAs(otherStudentUserId);

        assertThatThrownBy(() -> attendanceRecordService.percentageForEnrollment(enrollmentId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignedFacultyCanViewAStudentsAttendancePercentageForTheirOwnSection() {
        var s = setUp("REC14");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC14S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        attendanceRecordService.scan(session.sessionId(), session.rawToken());
        TestAuthentication.authenticateAs(s.faculty.userId());

        var pct = attendanceRecordService.percentageForEnrollment(enrollmentId);

        assertThat(pct.total()).isEqualTo(1);
    }

    @Test
    void unrelatedFacultyCannotViewAnotherSectionsAttendancePercentage() {
        var s = setUp("REC15");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC15S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        attendanceRecordService.scan(session.sessionId(), session.rawToken());
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), com.setu.identity.domain.RoleCode.FACULTY, "REC15-OTHER");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> attendanceRecordService.percentageForEnrollment(enrollmentId))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §guardian: guardian delegation was never wired into this HTTP path (delegatableForStudent
    // was missing entirely) — a guardian with a real, active VIEW_ATTENDANCE grant got the same blanket
    // AccessDeniedException as a stranger. Fixed to use the same delegatableForStudent idiom as
    // GradeService/RiskCalculationService/LearningProgressService.

    @Test
    void guardianWithActiveViewAttendanceDelegationCanViewTheStudentsAttendancePercentage() {
        var s = setUp("REC16");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC16S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        attendanceRecordService.scan(session.sessionId(), session.rawToken());

        var guardian = day10Fixtures.provisionGuardian(s.tenant, "REC16G");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        guardianAccessService.grantAccess(student.studentId(), guardian.guardianId(), null, Set.of("VIEW_ATTENDANCE"));
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        var pct = attendanceRecordService.percentageForEnrollment(enrollmentId);

        assertThat(pct.total()).isEqualTo(1);
        assertThat(pct.presentOrLate()).isEqualTo(1);
    }

    @Test
    void guardianWithoutAnyGrantCannotViewAttendancePercentageDespiteTheRealRelationship() {
        var s = setUp("REC17");
        var session = openSession(s);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "REC17S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        attendanceRecordService.scan(session.sessionId(), session.rawToken());

        var guardian = day10Fixtures.provisionGuardian(s.tenant, "REC17G");
        // Deliberately no guardianAccessService.grantAccess call — the relationship (a registered guardian in
        // the same tenant) exists, but zero guardian_access_grants rows do.

        TestAuthentication.authenticateAs(guardian.guardianUserId());

        assertThatThrownBy(() -> attendanceRecordService.percentageForEnrollment(enrollmentId))
            .isInstanceOf(AccessDeniedException.class);
    }
}
