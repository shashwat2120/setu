package com.setu.attendance.application;

import com.setu.attendance.application.FacultyAttendanceService.RecordResult;
import com.setu.attendance.application.StaffAttendancePolicyService.UpdateResult;
import com.setu.attendance.domain.FacultyAttendanceStatus;
import com.setu.attendance.domain.PolicyRules;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The staff-attendance policy end to end: defaults, versioned changes, who may read or change it, and that check-in/out and
 * administrator actions are judged under it. Deliberately NOT {@code @Transactional} (real commits, real concurrency). Time-dependent
 * results are made deterministic by choosing the POLICY (for example a 00:00 shift start, or a full day of 1440 minutes), never by
 * trusting the wall clock.
 */
@SpringBootTest
class StaffAttendancePolicyServiceTests {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata"); // every fixture college is created in Asia/Kolkata

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    StaffAttendancePolicyService policyService;
    @Autowired
    FacultyAttendanceService attendanceService;
    @Autowired
    FacultyRegularisationService regularisationService;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
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

    private static LocalDate today() {
        return LocalDate.now(KOLKATA);
    }

    private PolicyRules save(Day13TestFixtures.Scenario s, String start, String end, int grace, int full, int half) {
        var result = as(s.tenant().adminUserId(), () -> policyService.update(LocalTime.parse(start), LocalTime.parse(end), grace, full, half));
        assertThat(result).isInstanceOf(UpdateResult.Success.class);
        return ((UpdateResult.Success) result).rules();
    }

    // ---- defaults and versions ----

    @Test
    void aCollegeWithNoSavedPolicyGetsTheDefaultsAtVersionZero() {
        var s = day13Fixtures.provisionEnrolledScenario("POL1");

        var rules = as(s.faculty().userId(), policyService::currentForCaller).orElseThrow();

        assertThat(rules).isEqualTo(PolicyRules.defaults());
        assertThat(jdbc.queryForObject("select count(*) from attendance.staff_attendance_policies where tenant_id = ?", Integer.class, s.tenant().tenantId())).isZero();
    }

    @Test
    void everyChangeIsANewVersionAndTheOldOnesAreKept() {
        var s = day13Fixtures.provisionEnrolledScenario("POL2");

        var v1 = save(s, "08:30", "16:30", 10, 450, 225);
        var v2 = save(s, "08:30", "16:30", 20, 450, 225);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(as(s.faculty().userId(), policyService::currentForCaller).orElseThrow()).isEqualTo(v2);
        assertThat(jdbc.queryForList("select version from attendance.staff_attendance_policies where tenant_id = ? order by version", Integer.class,
            s.tenant().tenantId())).containsExactly(1, 2);
    }

    @Test
    void savingTheSameValuesAgainChangesNothing() {
        var s = day13Fixtures.provisionEnrolledScenario("POL3");
        save(s, "09:00", "17:00", 30, 480, 240);

        var again = as(s.tenant().adminUserId(), () -> policyService.update(LocalTime.of(9, 0), LocalTime.of(17, 0), 30, 480, 240));
        // the built-in defaults are version 0: restating them is also "no change"
        var other = day13Fixtures.provisionEnrolledScenario("POL3B");
        var defaultsAgain = as(other.tenant().adminUserId(), () -> policyService.update(LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 480, 240));

        assertThat(again).isInstanceOf(UpdateResult.NoChange.class);
        assertThat(defaultsAgain).isInstanceOf(UpdateResult.NoChange.class);
    }

    @Test
    void invalidRulesAreRefusedAndNothingIsSaved() {
        var s = day13Fixtures.provisionEnrolledScenario("POL4");
        UUID admin = s.tenant().adminUserId();

        assertThat(as(admin, () -> policyService.update(LocalTime.of(17, 0), LocalTime.of(9, 0), 15, 480, 240))).isInstanceOf(UpdateResult.InvalidRequest.class);
        assertThat(as(admin, () -> policyService.update(LocalTime.of(9, 0), LocalTime.of(17, 0), 500, 480, 240))).isInstanceOf(UpdateResult.InvalidRequest.class);
        assertThat(as(admin, () -> policyService.update(LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 240, 480))).isInstanceOf(UpdateResult.InvalidRequest.class);
        assertThat(jdbc.queryForObject("select count(*) from attendance.staff_attendance_policies where tenant_id = ?", Integer.class, s.tenant().tenantId())).isZero();
    }

    @Test
    void twoAdministratorsSavingAtOnceProduceOneNewVersionAndOneConflict() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("POL5");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<UpdateResult>> futures = List.of(
                pool.submit(() -> { go.await(); return as(s.tenant().adminUserId(), () -> policyService.update(LocalTime.of(8, 0), LocalTime.of(16, 0), 10, 450, 225)); }),
                pool.submit(() -> { go.await(); return as(s.tenant().adminUserId(), () -> policyService.update(LocalTime.of(10, 0), LocalTime.of(18, 0), 20, 420, 210)); }));
            go.countDown();
            long success = 0;
            long conflict = 0;
            for (var f : futures) {
                var r = f.get();
                if (r instanceof UpdateResult.Success) success++;
                if (r instanceof UpdateResult.Conflict) conflict++;
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);
            assertThat(jdbc.queryForList("select version from attendance.staff_attendance_policies where tenant_id = ?", Integer.class, s.tenant().tenantId())).containsExactly(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- who may read / change ----

    @Test
    void facultyAndAdminsReadItButOnlyAdminsChangeIt() {
        var s = day13Fixtures.provisionEnrolledScenario("POL6");

        assertThat(as(s.faculty().userId(), policyService::currentForCaller)).isPresent();
        assertThat(as(s.tenant().adminUserId(), policyService::currentForCaller)).isPresent();
        assertThatThrownBy(() -> as(s.faculty().userId(), () -> policyService.update(LocalTime.of(8, 0), LocalTime.of(16, 0), 10, 450, 225)))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(s.student().userId(), policyService::currentForCaller)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(s.student().userId(), () -> policyService.update(LocalTime.of(8, 0), LocalTime.of(16, 0), 10, 450, 225)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdminOnlyChangesAndSeesTheirOwnCollegesPolicy() {
        var s = day13Fixtures.provisionEnrolledScenario("POL7");
        var other = day13Fixtures.provisionEnrolledScenario("POL7X");

        save(s, "08:00", "16:00", 10, 450, 225);

        // each college's caller resolves THEIR OWN tenant: the other college is still on the defaults
        assertThat(as(other.tenant().adminUserId(), policyService::currentForCaller).orElseThrow()).isEqualTo(PolicyRules.defaults());
        assertThat(as(other.faculty().userId(), policyService::currentForCaller).orElseThrow().version()).isZero();
    }

    // ---- judged under the policy ----

    @Test
    void aCheckInIsJudgedUnderTheCollegesPolicyAndRecordsTheVersion() {
        var s = day13Fixtures.provisionEnrolledScenario("POL8");
        save(s, "00:00", "23:59", 0, 1440, 720); // shift starts at midnight with no grace: any check-in later than 00:00 is late

        var result = as(s.faculty().userId(), attendanceService::checkIn);

        var row = ((RecordResult.Success) result).record();
        assertThat(row.policyVersion()).isEqualTo(1);
        // whatever minute the test happens to run in, the stored value must equal what the saved rules say about that exact check-in time
        var rules = as(s.faculty().userId(), policyService::currentForCaller).orElseThrow();
        int expected = rules.lateMinutes(row.checkInAt().atZone(KOLKATA).toLocalTime());
        assertThat(row.lateMinutes()).isEqualTo(expected == 0 ? null : expected);
    }

    @Test
    void checkingInBeforeShiftPlusGraceIsNeverLate() {
        var s = day13Fixtures.provisionEnrolledScenario("POL9");
        save(s, "23:58", "23:59", 120, 2, 1); // the shift starts so late in the day that no check-in is ever past start + grace

        var row = ((RecordResult.Success) as(s.faculty().userId(), attendanceService::checkIn)).record();

        assertThat(row.lateMinutes()).isNull();
        assertThat(row.policyVersion()).isEqualTo(1);
    }

    @Test
    void checkingOutBeforeAFullDayBecomesAHalfDayWithShortHoursFlagged() {
        var s = day13Fixtures.provisionEnrolledScenario("POL10");
        save(s, "00:00", "23:59", 0, 1440, 720);
        as(s.faculty().userId(), attendanceService::checkIn);

        var row = ((RecordResult.Success) as(s.faculty().userId(), attendanceService::checkOut)).record();

        assertThat(row.workedMinutes()).isZero();
        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.HALF_DAY);
        assertThat(row.shortHours()).isTrue();
        var auditDetails = jdbc.queryForObject("select after_json::text from governance.audit_logs where action = 'FACULTY_CHECKED_OUT' and resource_id = ?",
            String.class, row.id());
        assertThat(auditDetails).contains("HALF_DAY").contains("\"shortHours\": true").contains("\"policyVersion\": 1");
    }

    @Test
    void aLaterPolicyChangeNeverRejudgesAnEarlierDay() {
        var s = day13Fixtures.provisionEnrolledScenario("POL11");
        as(s.faculty().userId(), attendanceService::checkIn); // judged under the defaults (version 0)

        save(s, "00:00", "23:59", 0, 1440, 720);

        var stored = as(s.faculty().userId(), () -> attendanceService.myToday()).orElseThrow();
        assertThat(stored.policyVersion()).isZero();
    }

    @Test
    void anApprovedRegularisationRecordsItsMetricsButKeepsTheStatusThePersonDecided() {
        var s = day13Fixtures.provisionEnrolledScenario("POL12");
        LocalDate day = today().minusDays(2);
        var request = ((FacultyRegularisationService.Result.Success) as(s.faculty().userId(), () -> regularisationService.request(
            day, FacultyAttendanceStatus.PRESENT, LocalTime.of(9, 30), LocalTime.of(17, 0), "Forgot to check in"))).request();

        as(s.tenant().adminUserId(), () -> regularisationService.approve(request.id(), null));

        var row = as(s.faculty().userId(), () -> attendanceService.history(s.faculty().facultyId(), day, day)).orElseThrow().get(0);
        assertThat(row.lateMinutes()).isEqualTo(30);      // 09:30 against a 09:00 start with 15 minutes' grace
        assertThat(row.workedMinutes()).isEqualTo(450);   // 09:30 to 17:00
        assertThat(row.shortHours()).isFalse();
        assertThat(row.policyVersion()).isZero();
        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.PRESENT); // 450 < 480, yet the approver decided PRESENT
    }

    @Test
    void policyChangesAreAuditedAndPublished() {
        var s = day13Fixtures.provisionEnrolledScenario("POL13");

        save(s, "08:00", "16:00", 10, 450, 225);

        assertThat(jdbc.queryForObject("select count(*) from governance.audit_logs where action = 'STAFF_ATTENDANCE_POLICY_CHANGED' and tenant_id = ?",
            Integer.class, s.tenant().tenantId())).isEqualTo(1);
        var event = jdbc.queryForMap("select event_type, payload_json ->> 'policyVersion' as version, payload_json ->> 'graceMinutes' as grace "
            + "from governance.outbox_events where aggregate_type = 'faculty_attendance_policy' and aggregate_id = ?", s.tenant().tenantId());
        assertThat(event.get("event_type")).isEqualTo("FacultyAttendancePolicyChanged");
        assertThat(event.get("version")).isEqualTo("1");
        assertThat(event.get("grace")).isEqualTo("10");
    }
}
