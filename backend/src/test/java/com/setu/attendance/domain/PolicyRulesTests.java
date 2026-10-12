package com.setu.attendance.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The staff-attendance policy arithmetic (docs/state-machines.md #25), exact at every boundary, with no clock or database involved. */
class PolicyRulesTests {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private final PolicyRules defaults = PolicyRules.defaults();

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(KOLKATA).toInstant();
    }

    // ---- defaults ----

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        assertThat(defaults.version()).isEqualTo(0);
        assertThat(defaults.isDefault()).isTrue();
        assertThat(defaults.shiftStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(defaults.shiftEnd()).isEqualTo(LocalTime.of(17, 0));
        assertThat(defaults.graceMinutes()).isEqualTo(15);
        assertThat(defaults.fullDayMinutes()).isEqualTo(480);
        assertThat(defaults.halfDayMinutes()).isEqualTo(240);
    }

    // ---- late ----

    @Test
    void arrivingWithinTheGracePeriodIsOnTimeAndOneMinutePastItIsLate() {
        assertThat(defaults.lateMinutes(LocalTime.of(8, 30))).isZero();   // early
        assertThat(defaults.lateMinutes(LocalTime.of(9, 0))).isZero();    // exactly on time
        assertThat(defaults.lateMinutes(LocalTime.of(9, 15))).isZero();   // last minute of grace
        assertThat(defaults.lateMinutes(LocalTime.of(9, 16))).isEqualTo(16); // counted from the shift start, not from the end of grace
        assertThat(defaults.lateMinutes(LocalTime.of(10, 30))).isEqualTo(90);
    }

    @Test
    void zeroGraceMakesTheFirstMinuteLate() {
        var strict = new PolicyRules(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 0, 480, 240);

        assertThat(strict.lateMinutes(LocalTime.of(9, 0))).isZero();
        assertThat(strict.lateMinutes(LocalTime.of(9, 1))).isEqualTo(1);
    }

    // ---- worked hours ----

    @Test
    void aFullDayNeedsTheFullMinutesAndAnythingLessIsAHalfDay() {
        assertThat(defaults.statusForWorkedMinutes(480)).isEqualTo(FacultyAttendanceStatus.PRESENT);
        assertThat(defaults.statusForWorkedMinutes(600)).isEqualTo(FacultyAttendanceStatus.PRESENT);
        assertThat(defaults.statusForWorkedMinutes(479)).isEqualTo(FacultyAttendanceStatus.HALF_DAY);
        assertThat(defaults.statusForWorkedMinutes(240)).isEqualTo(FacultyAttendanceStatus.HALF_DAY);
    }

    @Test
    void shortHoursMeansLessThanEvenAHalfDay() {
        assertThat(defaults.isShortHours(240)).isFalse();
        assertThat(defaults.isShortHours(239)).isTrue();
        assertThat(defaults.isShortHours(0)).isTrue();
    }

    // ---- validation ----

    @Test
    void anInvalidPolicyCannotBeBuilt() {
        assertThatThrownBy(() -> new PolicyRules(1, LocalTime.of(17, 0), LocalTime.of(9, 0), 15, 480, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRules(1, LocalTime.of(9, 0), LocalTime.of(9, 0), 15, 480, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRules(1, LocalTime.of(9, 0), LocalTime.of(17, 0), -1, 480, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRules(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 121, 480, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRules(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 240, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRules(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 480, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRules(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 1441, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRules(1, null, LocalTime.of(17, 0), 15, 480, 240)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- applied to an attendance day ----

    @Test
    void aDayCheckedInLateAndOutAfterAFullDayIsLateButStillPresent() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        var row = FacultyAttendance.checkIn(UUID.randomUUID(), UUID.randomUUID(), day, at(day, 9, 40));
        row.checkOut(at(day, 17, 45));

        row.applyPolicy(defaults, KOLKATA);
        row.applySelfCheckOutOutcome(defaults);

        assertThat(row.lateMinutes()).isEqualTo(40);
        assertThat(row.workedMinutes()).isEqualTo(485);
        assertThat(row.shortHours()).isFalse();
        assertThat(row.policyVersion()).isEqualTo(0);
        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.PRESENT);
    }

    @Test
    void leavingBeforeAFullDayBecomesAHalfDayAndBeforeAHalfDayAlsoFlagsShortHours() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        var halfDay = FacultyAttendance.checkIn(UUID.randomUUID(), UUID.randomUUID(), day, at(day, 9, 0));
        halfDay.checkOut(at(day, 14, 0)); // 300 minutes
        halfDay.applyPolicy(defaults, KOLKATA);
        halfDay.applySelfCheckOutOutcome(defaults);
        assertThat(halfDay.status()).isEqualTo(FacultyAttendanceStatus.HALF_DAY);
        assertThat(halfDay.shortHours()).isFalse();
        assertThat(halfDay.lateMinutes()).isNull();

        var tooShort = FacultyAttendance.checkIn(UUID.randomUUID(), UUID.randomUUID(), day, at(day, 9, 0));
        tooShort.checkOut(at(day, 10, 0)); // 60 minutes
        tooShort.applyPolicy(defaults, KOLKATA);
        tooShort.applySelfCheckOutOutcome(defaults);
        assertThat(tooShort.status()).isEqualTo(FacultyAttendanceStatus.HALF_DAY);
        assertThat(tooShort.shortHours()).isTrue();
    }

    @Test
    void anOpenDayHasLateMinutesButNoWorkedMinutesYet() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        var row = FacultyAttendance.checkIn(UUID.randomUUID(), UUID.randomUUID(), day, at(day, 9, 30));

        row.applyPolicy(defaults, KOLKATA);

        assertThat(row.lateMinutes()).isEqualTo(30);
        assertThat(row.workedMinutes()).isNull();
        assertThat(row.shortHours()).isFalse();
    }

    @Test
    void theCollegeTimezoneDecidesWhatTimeOfDayItWas() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        // 04:00 UTC is 09:30 in Kolkata (late) but 05:00 in Paris-less UTC terms — the same instant judged in two zones
        var instant = Instant.parse("2026-10-02T04:00:00Z");
        var row = FacultyAttendance.checkIn(UUID.randomUUID(), UUID.randomUUID(), day, instant);

        row.applyPolicy(defaults, KOLKATA);   // 09:30 local
        assertThat(row.lateMinutes()).isEqualTo(30);
        row.applyPolicy(defaults, ZoneId.of("UTC")); // 04:00 local — early
        assertThat(row.lateMinutes()).isNull();
    }

    @Test
    void whatAnAdministratorSetIsNeverOverriddenByTheAutomaticOutcome() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        var row = FacultyAttendance.checkIn(UUID.randomUUID(), UUID.randomUUID(), day, at(day, 9, 0));
        row.checkOut(at(day, 10, 0));
        row.correctByAdmin(FacultyAttendanceStatus.PRESENT, UUID.randomUUID(), "Covered a full day off-site", at(day, 18, 0));
        row.applyPolicy(defaults, KOLKATA);

        row.applySelfCheckOutOutcome(defaults);

        assertThat(row.status()).isEqualTo(FacultyAttendanceStatus.PRESENT); // 60 minutes worked, but the administrator decided
        assertThat(row.shortHours()).isTrue();                               // the flag is still recorded for visibility
    }

    @Test
    void movingADayToAbsentOrLeaveClearsEveryMetric() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        var row = FacultyAttendance.checkIn(UUID.randomUUID(), UUID.randomUUID(), day, at(day, 9, 40));
        row.checkOut(at(day, 11, 0));
        row.applyPolicy(defaults, KOLKATA);
        assertThat(row.lateMinutes()).isNotNull();

        row.correctByAdmin(FacultyAttendanceStatus.ON_LEAVE, UUID.randomUUID(), "approved leave", at(day, 18, 0));

        assertThat(row.lateMinutes()).isNull();
        assertThat(row.workedMinutes()).isNull();
        assertThat(row.policyVersion()).isNull();
        assertThat(row.shortHours()).isFalse();
    }
}
