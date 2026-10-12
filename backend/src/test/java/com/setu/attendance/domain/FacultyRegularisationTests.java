package com.setu.attendance.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The invariants the request entity enforces itself (docs/state-machines.md #26), independent of any service or database. */
class FacultyRegularisationTests {

    private static final Instant NOW = Instant.parse("2026-10-05T04:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);
    private static final Instant IN = Instant.parse("2026-10-02T04:00:00Z");
    private static final Instant OUT = Instant.parse("2026-10-02T11:30:00Z");

    private FacultyRegularisation pending() {
        return new FacultyRegularisation(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.PRESENT, IN, OUT,
            "Forgot to check in; I was teaching", NOW);
    }

    @Test
    void aNewRequestIsPendingAndKeepsTheReasonTrimmed() {
        var r = new FacultyRegularisation(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.PRESENT, null, null,
            "  Forgot to check in  ", NOW);

        assertThat(r.status()).isEqualTo(RegularisationStatus.PENDING);
        assertThat(r.isPending()).isTrue();
        assertThat(r.reason()).isEqualTo("Forgot to check in");
        assertThat(r.decidedBy()).isNull();
    }

    @Test
    void aRequestCannotAskForAbsentOrOmitAReason() {
        assertThatThrownBy(() -> new FacultyRegularisation(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.ABSENT, null, null, "x", NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FacultyRegularisation(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.PRESENT, null, null, " ", NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timesOnlyApplyToWorkDaysAndMustBeOrdered() {
        assertThatThrownBy(() -> new FacultyRegularisation(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.ON_LEAVE, IN, null, "x", NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FacultyRegularisation(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.PRESENT, null, OUT, "x", NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FacultyRegularisation(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.PRESENT, OUT, IN, "x", NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalRecordsWhoAndWhenAndIsFinal() {
        var r = pending();
        var admin = UUID.randomUUID();

        r.approve(admin, " ok ", NOW.plusSeconds(60));

        assertThat(r.status()).isEqualTo(RegularisationStatus.APPROVED);
        assertThat(r.decidedBy()).isEqualTo(admin);
        assertThat(r.decidedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(r.decisionNote()).isEqualTo("ok");
        assertThatThrownBy(() -> r.approve(admin, null, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.reject(admin, "no", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.withdraw(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectionNeedsANoteAndIsFinal() {
        var r = pending();
        assertThatThrownBy(() -> r.reject(UUID.randomUUID(), "  ", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(r.isPending()).isTrue(); // a refused rejection changes nothing

        r.reject(UUID.randomUUID(), "No record of you being on campus", NOW);

        assertThat(r.status()).isEqualTo(RegularisationStatus.REJECTED);
        assertThat(r.decisionNote()).isEqualTo("No record of you being on campus");
        assertThatThrownBy(() -> r.approve(UUID.randomUUID(), null, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withdrawalIsFinalAndRecordsNoDecider() {
        var r = pending();

        r.withdraw(NOW);

        assertThat(r.status()).isEqualTo(RegularisationStatus.WITHDRAWN);
        assertThat(r.decidedBy()).isNull();
        assertThatThrownBy(() -> r.approve(UUID.randomUUID(), null, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anApprovedRegularisationAppliesRequestedTimesToAnAttendanceDay() {
        var admin = UUID.randomUUID();
        var onLeave = FacultyAttendance.markedByAdmin(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.ON_LEAVE, admin, "leave", NOW);

        onLeave.regularise(FacultyAttendanceStatus.PRESENT, IN, OUT, admin, "Regularisation approved", NOW.plusSeconds(60));

        assertThat(onLeave.status()).isEqualTo(FacultyAttendanceStatus.PRESENT);
        assertThat(onLeave.checkInAt()).isEqualTo(IN);
        assertThat(onLeave.checkOutAt()).isEqualTo(OUT);
        assertThat(onLeave.source()).isEqualTo(FacultyAttendanceSource.ADMIN);
    }

    @Test
    void regularisingToLeaveClearsTimesAndKeepsAnExistingTimeWhenNoneIsRequested() {
        var admin = UUID.randomUUID();
        var day = FacultyAttendance.regularised(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.PRESENT, IN, null, admin, "r", NOW);

        day.regularise(FacultyAttendanceStatus.PRESENT, null, OUT, admin, "added the missing check-out", NOW);
        assertThat(day.checkInAt()).isEqualTo(IN); // not asked to change, so kept
        assertThat(day.checkOutAt()).isEqualTo(OUT);

        day.regularise(FacultyAttendanceStatus.ON_LEAVE, null, null, admin, "was on leave", NOW);
        assertThat(day.checkInAt()).isNull();
        assertThat(day.checkOutAt()).isNull();
    }

    @Test
    void regularisingCannotProduceACheckOutBeforeTheCheckIn() {
        var admin = UUID.randomUUID();
        var day = FacultyAttendance.regularised(UUID.randomUUID(), UUID.randomUUID(), DAY, FacultyAttendanceStatus.PRESENT, OUT, null, admin, "r", NOW);

        assertThatThrownBy(() -> day.regularise(FacultyAttendanceStatus.PRESENT, null, IN, admin, "bad", NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
