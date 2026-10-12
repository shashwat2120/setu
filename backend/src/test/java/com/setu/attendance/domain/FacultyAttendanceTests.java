package com.setu.attendance.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The invariants the entity itself enforces (docs/state-machines.md #25), independent of any service or database. */
class FacultyAttendanceTests {

    private static final Instant NOW = Instant.parse("2026-10-05T04:00:00Z");

    private FacultyAttendance checkedIn() {
        return FacultyAttendance.checkIn(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 10, 5), NOW);
    }

    @Test
    void selfCheckInIsPresentOpenAndSelfSourced() {
        var row = checkedIn();

        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.PRESENT);
        assertThat(row.source()).isEqualTo(FacultyAttendanceSource.SELF);
        assertThat(row.isCheckedIn()).isTrue();
        assertThat(row.isCheckedOut()).isFalse();
        assertThat(row.correctedBy()).isNull();
    }

    @Test
    void checkOutOnlyAfterCheckInAndOnlyOnce() {
        var adminMade = FacultyAttendance.markedByAdmin(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 10, 5),
            FacultyAttendanceStatus.PRESENT, UUID.randomUUID(), "entered from register", NOW);
        assertThatThrownBy(() -> adminMade.checkOut(NOW.plusSeconds(60))).isInstanceOf(IllegalStateException.class);

        var row = checkedIn();
        row.checkOut(NOW.plusSeconds(3600));
        assertThat(row.checkOutAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThatThrownBy(() -> row.checkOut(NOW.plusSeconds(7200))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checkOutNeverPrecedesCheckIn() {
        var row = checkedIn();

        row.checkOut(NOW.minusSeconds(300)); // clock skew

        assertThat(row.checkOutAt()).isEqualTo(row.checkInAt());
    }

    @Test
    void adminMarkAndCorrectionAlwaysNeedAReason() {
        assertThatThrownBy(() -> FacultyAttendance.markedByAdmin(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 10, 5),
            FacultyAttendanceStatus.ABSENT, UUID.randomUUID(), " ", NOW)).isInstanceOf(IllegalArgumentException.class);
        var row = checkedIn();
        assertThatThrownBy(() -> row.correctByAdmin(FacultyAttendanceStatus.ABSENT, UUID.randomUUID(), null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correctionToAbsentOrLeaveClearsWorkTimesButHalfDayKeepsThem() {
        var halfDay = checkedIn();
        halfDay.checkOut(NOW.plusSeconds(1800));
        halfDay.correctByAdmin(FacultyAttendanceStatus.HALF_DAY, UUID.randomUUID(), "left at noon", NOW.plusSeconds(3600));
        assertThat(halfDay.checkInAt()).isNotNull();
        assertThat(halfDay.checkOutAt()).isNotNull();

        var admin = UUID.randomUUID();
        halfDay.correctByAdmin(FacultyAttendanceStatus.ON_LEAVE, admin, "approved leave", NOW.plusSeconds(7200));
        assertThat(halfDay.checkInAt()).isNull();
        assertThat(halfDay.checkOutAt()).isNull();
        assertThat(halfDay.source()).isEqualTo(FacultyAttendanceSource.ADMIN);
        assertThat(halfDay.correctedBy()).isEqualTo(admin);
        assertThat(halfDay.correctionReason()).isEqualTo("approved leave");
    }

    @Test
    void onlyPresentAndHalfDayCarryWorkTimes() {
        assertThat(FacultyAttendanceStatus.PRESENT.hasWorkTimes()).isTrue();
        assertThat(FacultyAttendanceStatus.HALF_DAY.hasWorkTimes()).isTrue();
        assertThat(FacultyAttendanceStatus.ABSENT.hasWorkTimes()).isFalse();
        assertThat(FacultyAttendanceStatus.ON_LEAVE.hasWorkTimes()).isFalse();
    }
}
