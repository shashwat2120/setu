package com.setu.attendance.application;

import com.setu.attendance.application.FacultyRegularisationService.Result;
import com.setu.attendance.domain.FacultyAttendanceSource;
import com.setu.attendance.domain.FacultyAttendanceStatus;
import com.setu.attendance.domain.RegularisationStatus;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.testsupport.Day13TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
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

/** docs/state-machines.md #26 — "I forgot to check in": request, withdraw, approve/reject, and everything each is not allowed to do. */
@SpringBootTest
class FacultyRegularisationServiceTests {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata"); // every fixture college is created in Asia/Kolkata
    
    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    FacultyRegularisationService service;
    @Autowired
    FacultyAttendanceService attendanceService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    UserRoleRepository userRoleRepository;

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

    private static LocalDate daysAgo(int n) {
        return today().minusDays(n);
    }

    private Result.Success requested(Day13TestFixtures.Scenario s, LocalDate day, FacultyAttendanceStatus status, String in, String out) {
        var result = as(s.faculty().userId(), () -> service.request(day, status,
            in == null ? null : LocalTime.parse(in), out == null ? null : LocalTime.parse(out), "Forgot to check in; I was teaching"));
        assertThat(result).isInstanceOf(Result.Success.class);
        return (Result.Success) result;
    }

    private List<String> eventTypes(UUID facultyId) {
        return jdbc.queryForList("select event_type from governance.outbox_events where aggregate_type = 'faculty_attendance' "
            + "and aggregate_id = ? order by occurred_at, id", String.class, facultyId);
    }

    // ---- the main story ----

    @Test
    void aFacultyMemberWhoForgotToCheckInRequestsAndTheAdminApprovesAndTheDayIsCorrected() {
        var s = day13Fixtures.provisionEnrolledScenario("REG1");
        LocalDate day = daysAgo(2);

        var pending = requested(s, day, FacultyAttendanceStatus.PRESENT, "09:30", "17:00").request();
        assertThat(pending.status()).isEqualTo(RegularisationStatus.PENDING);

        var approved = as(s.tenant().adminUserId(), () -> service.approve(pending.id(), "Confirmed with the HoD"));

        assertThat(approved).isInstanceOf(Result.Success.class);
        var decided = ((Result.Success) approved).request();
        assertThat(decided.status()).isEqualTo(RegularisationStatus.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo(s.tenant().adminUserId());
        assertThat(decided.decisionNote()).isEqualTo("Confirmed with the HoD");

        var history = as(s.faculty().userId(), () -> attendanceService.history(s.faculty().facultyId(), day, day)).orElseThrow();
        assertThat(history).hasSize(1);
        var row = history.get(0);
        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.PRESENT);
        assertThat(row.source()).isEqualTo(FacultyAttendanceSource.ADMIN);
        assertThat(row.correctedBy()).isEqualTo(s.tenant().adminUserId());
        assertThat(row.correctionReason()).contains("Regularisation approved").contains("Forgot to check in").contains("Confirmed with the HoD");
        assertThat(row.checkInAt()).isEqualTo(day.atTime(9, 30).atZone(KOLKATA).toInstant());
        assertThat(row.checkOutAt()).isEqualTo(day.atTime(17, 0).atZone(KOLKATA).toInstant());

        assertThat(eventTypes(s.faculty().facultyId())).containsExactly(
            "FacultyRegularisationRequested", "FacultyAttendanceMarked", "FacultyRegularisationApproved");
    }

    @Test
    void approvingOverAnExistingLeaveDayCorrectsItAndEmitsACorrection() {
        var s = day13Fixtures.provisionEnrolledScenario("REG2");
        LocalDate day = daysAgo(3);
        as(s.tenant().adminUserId(), () -> attendanceService.markByAdmin(s.faculty().facultyId(), day, FacultyAttendanceStatus.ON_LEAVE, "Recorded as leave"));
        var pending = requested(s, day, FacultyAttendanceStatus.PRESENT, "10:00", null).request();

        as(s.tenant().adminUserId(), () -> service.approve(pending.id(), null));

        var row = as(s.faculty().userId(), () -> attendanceService.history(s.faculty().facultyId(), day, day)).orElseThrow().get(0);
        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.PRESENT);
        assertThat(row.checkInAt()).isEqualTo(day.atTime(10, 0).atZone(KOLKATA).toInstant());
        assertThat(row.checkOutAt()).isNull();
        assertThat(eventTypes(s.faculty().facultyId())).containsExactly(
            "FacultyAttendanceMarked", "FacultyRegularisationRequested", "FacultyAttendanceCorrected", "FacultyRegularisationApproved");
    }

    @Test
    void aRejectedRequestLeavesTheDayUntouchedAndNeedsANote() {
        var s = day13Fixtures.provisionEnrolledScenario("REG3");
        LocalDate day = daysAgo(2);
        var pending = requested(s, day, FacultyAttendanceStatus.PRESENT, null, null).request();

        var noNote = as(s.tenant().adminUserId(), () -> service.reject(pending.id(), "  "));
        var rejected = as(s.tenant().adminUserId(), () -> service.reject(pending.id(), "No record of you on campus that day"));

        assertThat(noNote).isInstanceOf(Result.InvalidRequest.class);
        var decided = ((Result.Success) rejected).request();
        assertThat(decided.status()).isEqualTo(RegularisationStatus.REJECTED);
        assertThat(decided.decisionNote()).isEqualTo("No record of you on campus that day");
        assertThat(as(s.faculty().userId(), () -> attendanceService.history(s.faculty().facultyId(), day, day)).orElseThrow()).isEmpty();
        assertThat(eventTypes(s.faculty().facultyId())).containsExactly("FacultyRegularisationRequested", "FacultyRegularisationRejected");
    }

    @Test
    void aMemberCanWithdrawTheirOwnPendingRequestAndNobodyCanDecideItAfterwards() {
        var s = day13Fixtures.provisionEnrolledScenario("REG4");
        var pending = requested(s, daysAgo(2), FacultyAttendanceStatus.PRESENT, null, null).request();

        var withdrawn = as(s.faculty().userId(), () -> service.withdraw(pending.id()));
        var again = as(s.faculty().userId(), () -> service.withdraw(pending.id()));
        var approve = as(s.tenant().adminUserId(), () -> service.approve(pending.id(), null));

        assertThat(((Result.Success) withdrawn).request().status()).isEqualTo(RegularisationStatus.WITHDRAWN);
        assertThat(again).isInstanceOf(Result.NotPending.class);
        assertThat(approve).isInstanceOf(Result.NotPending.class);
        assertThat(eventTypes(s.faculty().facultyId())).containsExactly("FacultyRegularisationRequested", "FacultyRegularisationWithdrawn");
    }

    @Test
    void aDecidedRequestCannotBeDecidedAgain() {
        var s = day13Fixtures.provisionEnrolledScenario("REG5");
        var pending = requested(s, daysAgo(2), FacultyAttendanceStatus.PRESENT, null, null).request();
        as(s.tenant().adminUserId(), () -> service.approve(pending.id(), null));

        assertThat(as(s.tenant().adminUserId(), () -> service.approve(pending.id(), null))).isInstanceOf(Result.NotPending.class);
        assertThat(as(s.tenant().adminUserId(), () -> service.reject(pending.id(), "too late"))).isInstanceOf(Result.NotPending.class);
    }

    // ---- request validation ----

    @Test
    void onlyPastDaysWithinTheWindowAreAccepted() {
        var s = day13Fixtures.provisionEnrolledScenario("REG6");
        UUID faculty = s.faculty().userId();

        assertThat(as(faculty, () -> service.request(today(), FacultyAttendanceStatus.PRESENT, null, null, "x"))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(as(faculty, () -> service.request(daysAgo(-3), FacultyAttendanceStatus.PRESENT, null, null, "x"))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(as(faculty, () -> service.request(daysAgo(45), FacultyAttendanceStatus.PRESENT, null, null, "x"))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(as(faculty, () -> service.request(daysAgo(1), FacultyAttendanceStatus.PRESENT, null, null, "x"))).isInstanceOf(Result.Success.class);
    }

    @Test
    void badCombinationsAreRefused() {
        var s = day13Fixtures.provisionEnrolledScenario("REG7");
        UUID faculty = s.faculty().userId();
        LocalDate day = daysAgo(2);

        assertThat(as(faculty, () -> service.request(day, FacultyAttendanceStatus.ABSENT, null, null, "x"))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(as(faculty, () -> service.request(day, FacultyAttendanceStatus.PRESENT, null, null, "  "))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(as(faculty, () -> service.request(day, FacultyAttendanceStatus.PRESENT, null, null, "x".repeat(501)))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(as(faculty, () -> service.request(day, FacultyAttendanceStatus.ON_LEAVE, LocalTime.of(9, 0), null, "x"))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(as(faculty, () -> service.request(day, FacultyAttendanceStatus.PRESENT, null, LocalTime.of(17, 0), "x"))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(as(faculty, () -> service.request(day, FacultyAttendanceStatus.PRESENT, LocalTime.of(17, 0), LocalTime.of(9, 0), "x"))).isInstanceOf(Result.InvalidRequest.class);
        assertThat(eventTypes(s.faculty().facultyId())).isEmpty(); // refused requests leave no trace in the outbox
    }

    @Test
    void onlyOneOpenRequestPerDayAndNothingToChangeIsRefused() {
        var s = day13Fixtures.provisionEnrolledScenario("REG8");
        LocalDate day = daysAgo(2);
        requested(s, day, FacultyAttendanceStatus.PRESENT, null, null);

        var duplicate = as(s.faculty().userId(), () -> service.request(day, FacultyAttendanceStatus.HALF_DAY, null, null, "again"));
        assertThat(duplicate).isInstanceOf(Result.AlreadyPending.class);

        LocalDate leaveDay = daysAgo(4);
        as(s.tenant().adminUserId(), () -> attendanceService.markByAdmin(s.faculty().facultyId(), leaveDay, FacultyAttendanceStatus.ON_LEAVE, "Approved leave"));
        var same = as(s.faculty().userId(), () -> service.request(leaveDay, FacultyAttendanceStatus.ON_LEAVE, null, null, "please"));
        assertThat(same).isInstanceOf(Result.NothingToChange.class);
    }

    @Test
    void twoSimultaneousRequestsForTheSameDayLeaveExactlyOnePending() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("REG9");
        LocalDate day = daysAgo(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Result>> futures = List.of(
                pool.submit(() -> { go.await(); return as(s.faculty().userId(), () -> service.request(day, FacultyAttendanceStatus.PRESENT, null, null, "a")); }),
                pool.submit(() -> { go.await(); return as(s.faculty().userId(), () -> service.request(day, FacultyAttendanceStatus.PRESENT, null, null, "b")); }));
            go.countDown();
            long success = 0;
            long pendingAlready = 0;
            for (var f : futures) {
                var r = f.get();
                if (r instanceof Result.Success) success++;
                if (r instanceof Result.AlreadyPending) pendingAlready++;
            }
            assertThat(success).isEqualTo(1);
            assertThat(pendingAlready).isEqualTo(1);
            assertThat(eventTypes(s.faculty().facultyId())).containsExactly("FacultyRegularisationRequested");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- who may do what ----

    @Test
    void onlyAFacultyMemberCanRequest() {
        var s = day13Fixtures.provisionEnrolledScenario("REG10");

        assertThat(as(s.student().userId(), () -> service.request(daysAgo(2), FacultyAttendanceStatus.PRESENT, null, null, "x"))).isInstanceOf(Result.NotAFaculty.class);
        assertThat(as(s.tenant().adminUserId(), () -> service.request(daysAgo(2), FacultyAttendanceStatus.PRESENT, null, null, "x"))).isInstanceOf(Result.NotAFaculty.class);
    }

    @Test
    void facultyCannotDecideOrListAndColleaguesCannotWithdrawEachOthersRequests() {
        var s = day13Fixtures.provisionEnrolledScenario("REG11");
        var colleague = day9Fixtures.provisionFacultyAssignedToSection(s.tenant(), s.sectionId(), "REG11C");
        var pending = requested(s, daysAgo(2), FacultyAttendanceStatus.PRESENT, null, null).request();

        assertThatThrownBy(() -> as(colleague.userId(), () -> service.approve(pending.id(), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(s.faculty().userId(), () -> service.approve(pending.id(), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(colleague.userId(), () -> service.listForTenant(s.tenant().tenantId(), null))).isInstanceOf(AccessDeniedException.class);
        assertThat(as(colleague.userId(), () -> service.withdraw(pending.id()))).isInstanceOf(Result.NotFound.class); // looks exactly like a missing id
        assertThat(as(colleague.userId(), service::myRequests)).isEmpty();
        assertThat(as(s.faculty().userId(), service::myRequests)).hasSize(1);
    }

    @Test
    void anAdminOfAnotherCollegeCanNeitherListNorDecide() {
        var s = day13Fixtures.provisionEnrolledScenario("REG12");
        var other = day13Fixtures.provisionEnrolledScenario("REG12X");
        var pending = requested(s, daysAgo(2), FacultyAttendanceStatus.PRESENT, null, null).request();

        assertThatThrownBy(() -> as(other.tenant().adminUserId(), () -> service.listForTenant(s.tenant().tenantId(), RegularisationStatus.PENDING)))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(other.tenant().adminUserId(), () -> service.approve(pending.id(), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(other.tenant().adminUserId(), () -> service.reject(pending.id(), "no"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdminListsThePendingQueueOldestFirst() {
        var s = day13Fixtures.provisionEnrolledScenario("REG13");
        var first = requested(s, daysAgo(3), FacultyAttendanceStatus.PRESENT, null, null).request();
        var second = requested(s, daysAgo(2), FacultyAttendanceStatus.HALF_DAY, null, null).request();
        as(s.tenant().adminUserId(), () -> service.reject(second.id(), "not accepted"));
        var third = requested(s, daysAgo(1), FacultyAttendanceStatus.ON_LEAVE, null, null).request();

        var queue = as(s.tenant().adminUserId(), () -> service.listForTenant(s.tenant().tenantId(), RegularisationStatus.PENDING));

        assertThat(queue).extracting(r -> r.id()).containsExactly(first.id(), third.id());
    }

    @Test
    void nobodyDecidesTheirOwnRequestEvenWithBothRoles() {
        var s = day13Fixtures.provisionEnrolledScenario("REG14");
        // make the faculty member ALSO a college admin of their college
        UUID adminRole = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), s.faculty().userId(), adminRole, s.tenant().tenantId(), Instant.now(), null));
        var pending = requested(s, daysAgo(2), FacultyAttendanceStatus.PRESENT, null, null).request();

        var approve = as(s.faculty().userId(), () -> service.approve(pending.id(), null));
        var reject = as(s.faculty().userId(), () -> service.reject(pending.id(), "self"));
        var byAnotherAdmin = as(s.tenant().adminUserId(), () -> service.approve(pending.id(), null));

        assertThat(approve).isInstanceOf(Result.OwnRequest.class);
        assertThat(reject).isInstanceOf(Result.OwnRequest.class);
        assertThat(byAnotherAdmin).isInstanceOf(Result.Success.class);
    }

    @Test
    void anUnknownRequestIsNotFound() {
        var s = day13Fixtures.provisionEnrolledScenario("REG15");

        assertThat(as(s.tenant().adminUserId(), () -> service.approve(UUID.randomUUID(), null))).isInstanceOf(Result.NotFound.class);
        assertThat(as(s.faculty().userId(), () -> service.withdraw(UUID.randomUUID()))).isInstanceOf(Result.NotFound.class);
    }
}
