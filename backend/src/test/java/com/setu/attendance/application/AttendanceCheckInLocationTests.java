package com.setu.attendance.application;

import com.setu.attendance.domain.AttendanceRecord;
import com.setu.attendance.domain.AttendanceRecordStatus;
import com.setu.attendance.domain.CheckInLocation;
import com.setu.attendance.infrastructure.AttendanceRecordRepository;
import com.setu.attendance.infrastructure.AttendanceSessionRepository;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a check-in came from. The promises: it is recorded for the instructor and never decides whether the check-in counts; a student who shares
 * nothing checks in exactly as before; and only the outcome is stored, never the student's coordinates.
 * Deliberately not {@code @Transactional}, for the reason in {@code AttendanceRecordTests}.
 */
@SpringBootTest
class AttendanceCheckInLocationTests {

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
    @Autowired
    AttendanceRecordRepository attendanceRecordRepository;
    @Autowired
    AttendanceSessionRepository attendanceSessionRepository;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId, UUID meetingId,
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
        UUID meetingId = day9Fixtures.provisionMeetingPattern(tenant, sectionId, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        return new Scenario(tenant, programId, curriculumId, sectionId, meetingId, faculty);
    }

    private record Opened(String rawToken, UUID sessionId) {
    }

    private Opened open(Scenario s, Double latitude, Double longitude) {
        TestAuthentication.authenticateAs(s.faculty.userId());
        try {
            var result = (AttendanceSessionService.OpenResult.Success) attendanceSessionService.open(
                s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS), latitude, longitude);
            return new Opened(result.rawToken(), result.sessionId());
        } finally {
            TestAuthentication.clear();
        }
    }

    private AttendanceRecord scanAsNewStudent(Scenario s, Opened session, String tag, AttendanceRecordService.LocationReport report) {
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, tag);
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        var result = attendanceRecordService.scan(session.sessionId(), session.rawToken(), report);
        assertThat(result).isInstanceOf(AttendanceRecordService.ScanResult.Success.class);
        UUID recordId = ((AttendanceRecordService.ScanResult.Success) result).recordId();
        return attendanceRecordRepository.findById(recordId).orElseThrow();
    }

    @Test
    void aCheckInInTheRoomIsRecordedNearWithItsDistanceAndAccuracy() {
        var s = setUp("LOC1");
        var session = open(s, LAT, LON);

        var record = scanAsNewStudent(s, session, "LOC1S", new AttendanceRecordService.LocationReport(LAT + 0.0003, LON, 12.0));

        assertThat(record.status()).isEqualTo(AttendanceRecordStatus.PRESENT);
        assertThat(record.locationStatus()).isEqualTo(CheckInLocation.NEAR);
        assertThat(record.locationDistanceMeters()).isBetween(30, 36);
        assertThat(record.locationAccuracyMeters()).isEqualTo(12);
    }

    @Test
    void aCheckInFromFarAwayIsFlaggedButStillCountsAsPresent() {
        var s = setUp("LOC2");
        var session = open(s, LAT, LON);

        var record = scanAsNewStudent(s, session, "LOC2S", new AttendanceRecordService.LocationReport(LAT + 0.05, LON, 15.0));

        assertThat(record.status()).as("location is a signal, never a gate").isEqualTo(AttendanceRecordStatus.PRESENT);
        assertThat(record.locationStatus()).isEqualTo(CheckInLocation.FAR);
        assertThat(record.locationDistanceMeters()).isGreaterThan(5_000);
    }

    @Test
    void aStudentWhoSharesNothingChecksInExactlyAsBeforeAndIsRecordedAsNoLocation() {
        var s = setUp("LOC3");
        var session = open(s, LAT, LON);

        var declined = scanAsNewStudent(s, session, "LOC3S", new AttendanceRecordService.LocationReport(null, null, null));
        assertThat(declined.status()).isEqualTo(AttendanceRecordStatus.PRESENT);
        assertThat(declined.locationStatus()).isEqualTo(CheckInLocation.NO_LOCATION);

        var oldClient = scanAsNewStudent(s, session, "LOC3T", null);
        assertThat(oldClient.status()).isEqualTo(AttendanceRecordStatus.PRESENT);
        assertThat(oldClient.locationStatus()).isEqualTo(CheckInLocation.NO_LOCATION);
        assertThat(oldClient.locationDistanceMeters()).isNull();
    }

    @Test
    void aSessionWithoutAClassroomPositionRecordsNoReferenceInsteadOfGuessing() {
        var s = setUp("LOC4");
        var session = open(s, null, null);

        var record = scanAsNewStudent(s, session, "LOC4S", new AttendanceRecordService.LocationReport(LAT, LON, 8.0));

        assertThat(record.status()).isEqualTo(AttendanceRecordStatus.PRESENT);
        assertThat(record.locationStatus()).isEqualTo(CheckInLocation.NO_REFERENCE);
        assertThat(record.locationDistanceMeters()).isNull();
    }

    @Test
    void aNonsensePositionNeverStopsAClassOrACheckIn() {
        var s = setUp("LOC5");
        var session = open(s, 95.0, 500.0);   // not a position on Earth: the session opens without one
        assertThat(attendanceSessionRepository.findById(session.sessionId()).orElseThrow().anchorLatitude()).isNull();

        var record = scanAsNewStudent(s, session, "LOC5S", new AttendanceRecordService.LocationReport(-200.0, 400.0, 5.0));

        assertThat(record.status()).isEqualTo(AttendanceRecordStatus.PRESENT);
        assertThat(record.locationStatus()).isEqualTo(CheckInLocation.NO_LOCATION);
    }

    @Test
    void aRepeatScanKeepsTheLocationOfTheFirstAndAddsNothing() {
        var s = setUp("LOC6");
        var session = open(s, LAT, LON);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LOC6S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());
        var first = (AttendanceRecordService.ScanResult.Success) attendanceRecordService.scan(
            session.sessionId(), session.rawToken(), new AttendanceRecordService.LocationReport(LAT, LON, 5.0));

        var again = attendanceRecordService.scan(session.sessionId(), session.rawToken(), new AttendanceRecordService.LocationReport(LAT + 0.5, LON, 5.0));

        assertThat(again).isInstanceOf(AttendanceRecordService.ScanResult.AlreadyRecorded.class);
        assertThat(attendanceRecordRepository.findById(first.recordId()).orElseThrow().locationStatus()).isEqualTo(CheckInLocation.NEAR);
    }

    @Test
    void theStudentsCoordinatesAreNotStoredAnywhere() {
        List<String> columns = jdbc.queryForList("""
            select column_name from information_schema.columns
            where table_schema = 'attendance' and table_name = 'attendance_records'
              and (column_name ilike '%lat%' or column_name ilike '%lon%' or column_name ilike '%coord%' or column_name ilike '%position%'
                   or column_name ilike '%gps%' or column_name ilike '%geo%')
            """, String.class);
        assertThat(columns).as("attendance_records keeps the outcome of the comparison, never the position itself").isEmpty();
    }

    @Test
    void theDatabaseRefusesAStatusOrNumberThatIsNotOneOfOurs() {
        var s = setUp("LOC7");
        var session = open(s, LAT, LON);
        var record = scanAsNewStudent(s, session, "LOC7S", new AttendanceRecordService.LocationReport(LAT, LON, 5.0));

        assertThatThrownBy(() -> jdbc.update("update attendance.attendance_records set location_status = 'ELSEWHERE' where id = ?", record.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update attendance.attendance_records set location_distance_meters = -3 where id = ?", record.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update attendance.attendance_sessions set anchor_latitude = 95, anchor_longitude = 10 where id = ?", session.sessionId()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update attendance.attendance_sessions set anchor_latitude = 10, anchor_longitude = null where id = ?", session.sessionId()))
            .as("both coordinates or neither").isInstanceOf(DataIntegrityViolationException.class);
    }
}
