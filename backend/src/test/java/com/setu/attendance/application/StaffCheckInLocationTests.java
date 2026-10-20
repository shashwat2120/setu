package com.setu.attendance.application;

import com.setu.attendance.application.FacultyAttendanceService.RecordResult;
import com.setu.attendance.domain.CheckInLocation;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where staff check in from. The promises, each asserted: it is OFF until the college switches it on, and while it is off a reported position is
 * ignored and nothing about it is stored; when on, only the outcome against the campus is stored, never the coordinates; it never stops or changes a
 * check-in; only an administrator changes the setting and sees the campus coordinates. Not {@code @Transactional}, like the other staff attendance tests.
 */
@SpringBootTest
class StaffCheckInLocationTests {

    private static final double CAMPUS_LAT = 12.9716;
    private static final double CAMPUS_LON = 77.5946;

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    FacultyAttendanceService service;
    @Autowired
    StaffLocationPolicyService policyService;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private <T> T as(UUID userId, Callable<T> call) {
        TestAuthentication.authenticateAs(userId);
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            TestAuthentication.clear();
        }
    }

    private void switchOn(Day13TestFixtures.Scenario s, int radius) {
        var result = as(s.tenant().adminUserId(), () -> policyService.update(true, CAMPUS_LAT, CAMPUS_LON, radius));
        assertThat(result).isInstanceOf(StaffLocationPolicyService.UpdateResult.Success.class);
    }

    private RecordResult checkInFrom(Day13TestFixtures.Scenario s, Double lat, Double lon, Double accuracy) {
        return as(s.faculty().userId(), () -> service.checkInFrom(new AttendanceRecordService.LocationReport(lat, lon, accuracy)));
    }

    @Test
    void aCollegeThatHasNotSwitchedItOnIgnoresAReportedPositionAndStoresNothing() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL1");

        var result = checkInFrom(s, CAMPUS_LAT, CAMPUS_LON, 10.0);

        assertThat(result).isInstanceOf(RecordResult.Success.class);
        var row = ((RecordResult.Success) result).record();
        assertThat(row.locationStatus()).isNull();
        assertThat(row.locationDistanceMeters()).isNull();
        assertThat(row.locationAccuracyMeters()).isNull();
    }

    @Test
    void onTheCampusIsRecordedNearWithTheDistanceAndAccuracy() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL2");
        switchOn(s, 300);

        var row = ((RecordResult.Success) checkInFrom(s, CAMPUS_LAT + 0.0009, CAMPUS_LON, 15.0)).record();   // about 100 m away

        assertThat(row.locationStatus()).isEqualTo(CheckInLocation.NEAR);
        assertThat(row.locationDistanceMeters()).isBetween(90, 110);
        assertThat(row.locationAccuracyMeters()).isEqualTo(15);
    }

    @Test
    void farFromTheCampusIsRecordedFarAndStillCounts() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL3");
        switchOn(s, 300);

        var result = checkInFrom(s, CAMPUS_LAT + 0.05, CAMPUS_LON, 20.0);   // about 5.5 km away

        assertThat(result).isInstanceOf(RecordResult.Success.class);
        var row = ((RecordResult.Success) result).record();
        assertThat(row.locationStatus()).isEqualTo(CheckInLocation.FAR);
        assertThat(row.status().name()).isEqualTo("PRESENT");   // where they were never changed the check-in
        assertThat(row.checkInAt()).isNotNull();
    }

    @Test
    void theCollegesOwnRadiusDecidesWhatIsOnCampus() {
        var wide = day13Fixtures.provisionEnrolledScenario("SCL4");
        switchOn(wide, 1000);
        var narrow = day13Fixtures.provisionEnrolledScenario("SCL5");
        switchOn(narrow, 50);

        var seenFromWide = ((RecordResult.Success) checkInFrom(wide, CAMPUS_LAT + 0.0045, CAMPUS_LON, 0.0)).record();      // about 500 m
        var seenFromNarrow = ((RecordResult.Success) checkInFrom(narrow, CAMPUS_LAT + 0.0045, CAMPUS_LON, 0.0)).record();

        assertThat(seenFromWide.locationStatus()).isEqualTo(CheckInLocation.NEAR);
        assertThat(seenFromNarrow.locationStatus()).isEqualTo(CheckInLocation.FAR);
    }

    @Test
    void aStaffMemberWhoSharesNothingChecksInAllTheSame() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL6");
        switchOn(s, 300);

        var withoutBody = as(s.faculty().userId(), () -> service.checkIn());

        assertThat(withoutBody).isInstanceOf(RecordResult.Success.class);
        var row = ((RecordResult.Success) withoutBody).record();
        assertThat(row.locationStatus()).isEqualTo(CheckInLocation.NO_LOCATION);
        assertThat(row.locationDistanceMeters()).isNull();
    }

    @Test
    void anImpossiblePositionIsNoLocationNotAnError() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL7");
        switchOn(s, 300);

        var row = ((RecordResult.Success) checkInFrom(s, 999.0, 5.0, 10.0)).record();

        assertThat(row.locationStatus()).isEqualTo(CheckInLocation.NO_LOCATION);
    }

    @Test
    void theCoordinatesAreNeverStoredAnywhere() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL8");
        switchOn(s, 300);
        double lat = 12.97123456, lon = 77.59456789;

        checkInFrom(s, lat, lon, 10.0);

        List<String> columns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_schema = 'attendance' and table_name = 'faculty_attendance'", String.class);
        assertThat(columns).noneMatch(c -> c.contains("latitude") || c.contains("longitude") || c.equals("lat") || c.equals("lon"));
        // the reported digits are in no row of the day record (a sweep of the whole row as text) nor in the audit trail
        String rowText = jdbc.queryForObject("select f::text from attendance.faculty_attendance f where faculty_id = ?", String.class, s.faculty().facultyId());
        assertThat(rowText).doesNotContain("12.9712").doesNotContain("77.5945");
        Integer audited = jdbc.queryForObject("select count(*) from governance.audit_logs where after_json::text like '%12.9712%' or after_json::text like '%77.5945%' or before_json::text like '%12.9712%'",
            Integer.class);
        assertThat(audited).isZero();
    }

    @Test
    void switchingItOnNeedsTheCampusPositionAndASaneRadius() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL9");
        var admin = s.tenant().adminUserId();

        assertThat(as(admin, () -> policyService.update(true, null, null, 300))).isInstanceOf(StaffLocationPolicyService.UpdateResult.InvalidRequest.class);
        assertThat(as(admin, () -> policyService.update(true, CAMPUS_LAT, null, 300))).isInstanceOf(StaffLocationPolicyService.UpdateResult.InvalidRequest.class);
        assertThat(as(admin, () -> policyService.update(true, 200.0, CAMPUS_LON, 300))).isInstanceOf(StaffLocationPolicyService.UpdateResult.InvalidRequest.class);
        assertThat(as(admin, () -> policyService.update(true, CAMPUS_LAT, CAMPUS_LON, 10))).isInstanceOf(StaffLocationPolicyService.UpdateResult.InvalidRequest.class);
        assertThat(as(admin, () -> policyService.update(true, CAMPUS_LAT, CAMPUS_LON, 9000))).isInstanceOf(StaffLocationPolicyService.UpdateResult.InvalidRequest.class);
        assertThat(as(admin, () -> policyService.update(false, null, null, 300))).isInstanceOf(StaffLocationPolicyService.UpdateResult.Success.class);
    }

    @Test
    void theDatabaseItselfRefusesHalfACampusPositionAndSwitchingOnWithoutOne() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL10");
        UUID tenantId = s.tenant().tenantId();

        assertThatThrownBy(() -> jdbc.update("insert into attendance.staff_location_policies (tenant_id, enabled, campus_latitude) values (?, false, 10)", tenantId))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into attendance.staff_location_policies (tenant_id, enabled) values (?, true)", tenantId))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void onlyAnAdministratorChangesItAndOnlyAnAdministratorSeesTheCoordinates() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL11");
        switchOn(s, 400);

        assertThatThrownBy(() -> as(s.faculty().userId(), () -> policyService.update(false, null, null, 300))).isInstanceOf(AccessDeniedException.class);

        var forFaculty = as(s.faculty().userId(), policyService::currentForCaller).orElseThrow();
        assertThat(forFaculty.enabled()).isTrue();
        assertThat(forFaculty.radiusMeters()).isEqualTo(400);
        assertThat(forFaculty.campusLatitude()).isNull();
        assertThat(forFaculty.campusLongitude()).isNull();

        var forAdmin = as(s.tenant().adminUserId(), policyService::currentForCaller).orElseThrow();
        assertThat(forAdmin.campusLatitude()).isEqualTo(CAMPUS_LAT);
        assertThat(forAdmin.campusLongitude()).isEqualTo(CAMPUS_LON);
    }

    @Test
    void aStudentCannotReadOrChangeIt() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL12");
        var studentUser = s.student().userId();

        assertThatThrownBy(() -> as(studentUser, policyService::currentForCaller)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(studentUser, () -> policyService.update(false, null, null, 300))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void everyChangeIsAuditedWithWhoMadeIt() {
        var s = day13Fixtures.provisionEnrolledScenario("SCL13");
        switchOn(s, 300);

        Integer rows = jdbc.queryForObject("select count(*) from governance.audit_logs where action = 'STAFF_LOCATION_POLICY_CHANGED' "
            + "and tenant_id = ? and actor_user_id = ?", Integer.class, s.tenant().tenantId(), s.tenant().adminUserId());

        assertThat(rows).isEqualTo(1);
    }
}
