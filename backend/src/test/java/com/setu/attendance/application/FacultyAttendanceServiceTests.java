package com.setu.attendance.application;

import com.setu.attendance.application.FacultyAttendanceService.RecordResult;
import com.setu.attendance.domain.FacultyAttendanceSource;
import com.setu.attendance.domain.FacultyAttendanceStatus;
import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.domain.FacultyStatus;
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
 * docs/state-machines.md #25 — every rule of the faculty (staff) attendance day record. Deliberately not
 * {@code @Transactional}: the concurrency test needs real, separately committed transactions.
 */
@SpringBootTest
class FacultyAttendanceServiceTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    FacultyAttendanceService service;
    @Autowired
    FacultyProfileService facultyProfileService;
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

    /** Event types written to the transactional outbox for one faculty member, oldest first. */
    private List<String> eventTypes(UUID facultyId) {
        return jdbc.queryForList("select event_type from governance.outbox_events where aggregate_type = 'faculty_attendance' "
            + "and aggregate_id = ? order by occurred_at, id", String.class, facultyId);
    }

    private LocalDate todayInCollegeTimezone() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata")); // the test fixtures create every college in Asia/Kolkata
    }

    // ---- check-in / check-out (own day) ----

    @Test
    void facultyChecksInOnceAndTheDayIsTheCollegesDayNotUtc() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT1");

        var first = as(s.faculty().userId(), service::checkIn);
        var second = as(s.faculty().userId(), service::checkIn);

        assertThat(first).isInstanceOf(RecordResult.Success.class);
        var row = ((RecordResult.Success) first).record();
        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.PRESENT);
        assertThat(row.source()).isEqualTo(FacultyAttendanceSource.SELF);
        assertThat(row.checkInAt()).isNotNull();
        assertThat(row.checkOutAt()).isNull();
        assertThat(row.attendanceDate()).isEqualTo(todayInCollegeTimezone());
        assertThat(second).isInstanceOf(RecordResult.AlreadyRecorded.class);
    }

    @Test
    void checkOutNeedsACheckInAndWorksOnlyOnce() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT2");

        assertThat(as(s.faculty().userId(), service::checkOut)).isInstanceOf(RecordResult.NotCheckedIn.class);
        as(s.faculty().userId(), service::checkIn);
        var out = as(s.faculty().userId(), service::checkOut);
        var again = as(s.faculty().userId(), service::checkOut);

        assertThat(out).isInstanceOf(RecordResult.Success.class);
        var row = ((RecordResult.Success) out).record();
        assertThat(row.checkOutAt()).isAfterOrEqualTo(row.checkInAt());
        assertThat(again).isInstanceOf(RecordResult.AlreadyCheckedOut.class);
    }

    @Test
    void nobodyButAFacultyMemberCanCheckIn() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT3");

        assertThat(as(s.student().userId(), service::checkIn)).isInstanceOf(RecordResult.NotAFaculty.class);
        // a college admin is not a faculty member either — and holds no check-in permission at all
        assertThat(as(s.tenant().adminUserId(), service::checkIn)).isInstanceOf(RecordResult.NotAFaculty.class);
    }

    @Test
    void aFacultyProfileThatIsNotActiveCannotCheckIn() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT4");
        as(s.tenant().adminUserId(), () -> facultyProfileService.changeStatus(s.faculty().facultyId(), FacultyStatus.SUSPENDED));

        var result = as(s.faculty().userId(), service::checkIn);

        assertThat(result).isInstanceOf(RecordResult.ProfileNotActive.class);
        assertThat(((RecordResult.ProfileNotActive) result).status()).isEqualTo("SUSPENDED");
    }

    @Test
    void twoSimultaneousCheckInsProduceExactlyOneRecord() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("FAT5");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<RecordResult>> futures = List.of(
                pool.submit(() -> { go.await(); return as(s.faculty().userId(), service::checkIn); }),
                pool.submit(() -> { go.await(); return as(s.faculty().userId(), service::checkIn); }));
            go.countDown();
            long successes = 0;
            long alreadyRecorded = 0;
            for (var f : futures) {
                var r = f.get();
                if (r instanceof RecordResult.Success) successes++;
                if (r instanceof RecordResult.AlreadyRecorded) alreadyRecorded++;
            }
            assertThat(successes).isEqualTo(1);
            assertThat(alreadyRecorded).isEqualTo(1);
            assertThat(eventTypes(s.faculty().facultyId())).containsExactly("FacultyCheckedIn");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- administrator mark / correction ----

    @Test
    void adminMarksADayAndALaterSelfCheckInDoesNotOverrideIt() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT6");
        LocalDate today = todayInCollegeTimezone();

        var marked = as(s.tenant().adminUserId(),
            () -> service.markByAdmin(s.faculty().facultyId(), today, FacultyAttendanceStatus.ON_LEAVE, "Approved medical leave"));
        var checkIn = as(s.faculty().userId(), service::checkIn);

        assertThat(marked).isInstanceOf(RecordResult.Success.class);
        var row = ((RecordResult.Success) marked).record();
        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.ON_LEAVE);
        assertThat(row.source()).isEqualTo(FacultyAttendanceSource.ADMIN);
        assertThat(row.correctedBy()).isEqualTo(s.tenant().adminUserId());
        assertThat(row.correctionReason()).isEqualTo("Approved medical leave");
        assertThat(checkIn).isInstanceOf(RecordResult.AlreadyRecorded.class);
        assertThat(((RecordResult.AlreadyRecorded) checkIn).record().status()).isEqualTo(FacultyAttendanceStatus.ON_LEAVE);
    }

    @Test
    void adminCorrectingToAbsentClearsTheWorkTimesAndKeepsWhoAndWhy() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT7");
        as(s.faculty().userId(), service::checkIn);
        as(s.faculty().userId(), service::checkOut);

        var corrected = as(s.tenant().adminUserId(), () -> service.markByAdmin(
            s.faculty().facultyId(), todayInCollegeTimezone(), FacultyAttendanceStatus.ABSENT, "Badge reader fault; was not on campus"));

        var row = ((RecordResult.Success) corrected).record();
        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.ABSENT);
        assertThat(row.checkInAt()).isNull();
        assertThat(row.checkOutAt()).isNull();
        assertThat(row.source()).isEqualTo(FacultyAttendanceSource.ADMIN);
        assertThat(row.correctionReason()).contains("Badge reader fault");
    }

    @Test
    void adminCanMarkAPastDayButNotAFutureDayAndMustGiveAReason() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT8");
        UUID facultyId = s.faculty().facultyId();
        UUID admin = s.tenant().adminUserId();

        var past = as(admin, () -> service.markByAdmin(facultyId, todayInCollegeTimezone().minusDays(3), FacultyAttendanceStatus.PRESENT, "Entered from register"));
        var future = as(admin, () -> service.markByAdmin(facultyId, todayInCollegeTimezone().plusDays(2), FacultyAttendanceStatus.PRESENT, "Planned"));
        var noReason = as(admin, () -> service.markByAdmin(facultyId, todayInCollegeTimezone(), FacultyAttendanceStatus.ABSENT, "  "));

        assertThat(past).isInstanceOf(RecordResult.Success.class);
        assertThat(future).isInstanceOf(RecordResult.InvalidRequest.class);
        assertThat(noReason).isInstanceOf(RecordResult.InvalidRequest.class);
    }

    @Test
    void aFacultyMemberCannotMarkOrCorrectAttendanceNotEvenTheirOwn() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT9");

        assertThatThrownBy(() -> as(s.faculty().userId(), () -> service.markByAdmin(
            s.faculty().facultyId(), todayInCollegeTimezone(), FacultyAttendanceStatus.PRESENT, "self-service")))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void markingAnUnknownFacultyIsNotFound() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT10");

        var result = as(s.tenant().adminUserId(), () -> service.markByAdmin(
            UUID.randomUUID(), todayInCollegeTimezone(), FacultyAttendanceStatus.PRESENT, "x"));

        assertThat(result).isInstanceOf(RecordResult.FacultyNotFound.class);
    }

    // ---- Kafka outbox events ----

    @Test
    void everyChangeWritesItsOutboxEventInOrderAndRefusedActionsWriteNone() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT15");
        UUID facultyId = s.faculty().facultyId();

        as(s.faculty().userId(), service::checkIn);
        as(s.faculty().userId(), service::checkIn); // refused: already recorded
        as(s.faculty().userId(), service::checkOut);
        as(s.faculty().userId(), service::checkOut); // refused: already checked out
        as(s.student().userId(), service::checkIn); // refused: not faculty
        as(s.tenant().adminUserId(), () -> service.markByAdmin(facultyId, todayInCollegeTimezone(), FacultyAttendanceStatus.HALF_DAY, "Left at noon"));
        as(s.tenant().adminUserId(), () -> service.markByAdmin(facultyId, todayInCollegeTimezone(), FacultyAttendanceStatus.ABSENT, "  ")); // refused: no reason

        assertThat(eventTypes(facultyId)).containsExactly("FacultyCheckedIn", "FacultyCheckedOut", "FacultyAttendanceCorrected");
    }

    @Test
    void anAdminMarkOfAFreshDayEmitsMarkedWithWhoWhyAndTenant() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT16");
        UUID facultyId = s.faculty().facultyId();
        LocalDate day = todayInCollegeTimezone().minusDays(2);

        as(s.tenant().adminUserId(), () -> service.markByAdmin(facultyId, day, FacultyAttendanceStatus.ON_LEAVE, "Approved medical leave"));

        var row = jdbc.queryForMap("select event_type, tenant_id::text as tenant_id, payload_json ->> 'status' as status, "
            + "payload_json ->> 'actorUserId' as actor, payload_json ->> 'reason' as reason, payload_json ->> 'attendanceDate' as day, "
            + "payload_json ->> 'previousStatus' as previous from governance.outbox_events "
            + "where aggregate_type = 'faculty_attendance' and aggregate_id = ?", facultyId);
        assertThat(row.get("event_type")).isEqualTo("FacultyAttendanceMarked");
        assertThat(row.get("tenant_id")).isEqualTo(s.tenant().tenantId().toString());
        assertThat(row.get("status")).isEqualTo("ON_LEAVE");
        assertThat(row.get("actor")).isEqualTo(s.tenant().adminUserId().toString());
        assertThat(row.get("reason")).isEqualTo("Approved medical leave");
        assertThat(row.get("day")).isEqualTo(day.toString());
        assertThat(row.get("previous")).isNull();
    }

    @Test
    void aCorrectionEventCarriesThePreviousStatus() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT17");
        UUID facultyId = s.faculty().facultyId();
        as(s.faculty().userId(), service::checkIn);

        as(s.tenant().adminUserId(), () -> service.markByAdmin(facultyId, todayInCollegeTimezone(), FacultyAttendanceStatus.ABSENT, "Not on campus"));

        var previous = jdbc.queryForObject("select payload_json ->> 'previousStatus' from governance.outbox_events "
            + "where aggregate_type = 'faculty_attendance' and aggregate_id = ? and event_type = 'FacultyAttendanceCorrected'", String.class, facultyId);
        assertThat(previous).isEqualTo("PRESENT");
    }


    // ---- reads / tenant isolation ----

    @Test
    void facultyReadsOwnHistoryButNotAnotherFacultysAndAdminReadsAnyone() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT11");
        var colleague = day9Fixtures.provisionFacultyAssignedToSection(s.tenant(), s.sectionId(), "FAT11C");
        as(s.faculty().userId(), service::checkIn);
        LocalDate from = todayInCollegeTimezone().minusDays(7);
        LocalDate to = todayInCollegeTimezone();

        var own = as(s.faculty().userId(), () -> service.history(s.faculty().facultyId(), from, to));
        var asAdmin = as(s.tenant().adminUserId(), () -> service.history(s.faculty().facultyId(), from, to));

        assertThat(own).get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        assertThat(asAdmin).get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        assertThatThrownBy(() -> as(colleague.userId(), () -> service.history(s.faculty().facultyId(), from, to)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdminOfAnotherCollegeCanNeitherReadNorMarkNorList() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT12");
        var other = day13Fixtures.provisionEnrolledScenario("FAT12X");
        LocalDate today = todayInCollegeTimezone();
        as(s.faculty().userId(), service::checkIn);

        assertThatThrownBy(() -> as(other.tenant().adminUserId(), () -> service.history(s.faculty().facultyId(), today.minusDays(1), today)))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(other.tenant().adminUserId(), () -> service.markByAdmin(
            s.faculty().facultyId(), today, FacultyAttendanceStatus.ABSENT, "cross-tenant")))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(other.tenant().adminUserId(), () -> service.listForDate(s.tenant().tenantId(), today)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void onlyAdminsCanListTheWholeCollegeForADay() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT13");
        as(s.faculty().userId(), service::checkIn);
        LocalDate today = todayInCollegeTimezone();

        var rows = as(s.tenant().adminUserId(), () -> service.listForDate(s.tenant().tenantId(), today));

        assertThat(rows).extracting(r -> r.facultyId()).contains(s.faculty().facultyId());
        assertThatThrownBy(() -> as(s.faculty().userId(), () -> service.listForDate(s.tenant().tenantId(), today)))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(s.student().userId(), () -> service.listForDate(s.tenant().tenantId(), today)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void historyRangeIsCapped() {
        var s = day13Fixtures.provisionEnrolledScenario("FAT14");
        LocalDate today = todayInCollegeTimezone();

        assertThatThrownBy(() -> as(s.tenant().adminUserId(), () -> service.history(s.faculty().facultyId(), today.minusDays(400), today)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(s.tenant().adminUserId(), () -> service.history(s.faculty().facultyId(), today, today.minusDays(1))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
