package com.setu.career.application;

import com.setu.career.domain.PlacementRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The placement figures as pure arithmetic: who is counted, what a percentage is of, and what a missing package means. */
class PlacementStatisticsTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CS = UUID.randomUUID();
    private static final UUID ARTS = UUID.randomUUID();

    private static PlacementRecord placement(UUID student, String employer, String ctc) {
        return new PlacementRecord(UUID.randomUUID(), TENANT, student, null, employer, "Role", ctc == null ? null : new BigDecimal(ctc), LocalDate.of(2026, 10, 1));
    }

    @Test
    void aCollegeWithNoPlacementsHasZeroesAndNoPackagesNotInventedOnes() {
        var r = PlacementStatistics.compute(List.of(), Map.of(CS, 40, ARTS, 10), Map.of());

        assertThat(r.placedStudents()).isZero();
        assertThat(r.placements()).isZero();
        assertThat(r.activeStudents()).isEqualTo(50);
        assertThat(r.placedPercentage()).isEqualTo(0.0);
        assertThat(r.packages()).isEqualTo(new PlacementStatistics.Packages(0, null, null, null));
        assertThat(r.topRecruiters()).isEmpty();
    }

    @Test
    void aCollegeWithNoStudentsInAnyProgramHasNoPercentageRatherThanADivisionByZero() {
        var s = UUID.randomUUID();

        var r = PlacementStatistics.compute(List.of(placement(s, "Acme", "600000")), Map.of(), Map.of());

        assertThat(r.placedPercentage()).isNull();
        assertThat(r.placedStudents()).isEqualTo(1);
        assertThat(r.byProgram()).isEmpty();
    }

    @Test
    void percentagesAreOfStudentsInEachProgramAndAStudentPlacedTwiceCountsOnce() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var placements = List.of(placement(a, "Acme", "600000"), placement(a, "Globex", "900000"), placement(b, "Acme", "500000"), placement(c, "Initech", "400000"));

        var r = PlacementStatistics.compute(placements, Map.of(CS, 8, ARTS, 4), Map.of(a, CS, b, CS, c, ARTS));

        assertThat(r.placements()).isEqualTo(4);
        assertThat(r.placedStudents()).isEqualTo(3);
        assertThat(r.byProgram()).containsExactly(new PlacementStatistics.ProgramRow(CS, 8, 2, 25.0), new PlacementStatistics.ProgramRow(ARTS, 4, 1, 25.0));
        assertThat(r.activeStudents()).isEqualTo(12);
        assertThat(r.placedInCohorts()).isEqualTo(3);
        assertThat(r.placedPercentage()).isEqualTo(25.0);
    }

    @Test
    void aPlacedStudentInNoActiveProgramIsPlacedButNotInAnyPercentage() {
        var graduate = UUID.randomUUID();
        var current = UUID.randomUUID();

        var r = PlacementStatistics.compute(List.of(placement(graduate, "Acme", "600000"), placement(current, "Acme", "600000")), Map.of(CS, 10), Map.of(current, CS));

        assertThat(r.placedStudents()).isEqualTo(2);
        assertThat(r.placedInCohorts()).isEqualTo(1);
        assertThat(r.placedPercentage()).isEqualTo(10.0);
    }

    @Test
    void aProgramsPlacedCountNeverExceedsItsStudents() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();

        var r = PlacementStatistics.compute(List.of(placement(a, "Acme", "1"), placement(b, "Acme", "1")), Map.of(CS, 1), Map.of(a, CS, b, CS));

        assertThat(r.byProgram()).singleElement().satisfies(row -> {
            assertThat(row.placed()).isEqualTo(1);
            assertThat(row.placedPercentage()).isEqualTo(100.0);
        });
    }

    @Test
    void packagesAreInLakhsAndCountOnlyPlacementsThatStateOne() {
        var placements = List.of(placement(UUID.randomUUID(), "Acme", "600000"), placement(UUID.randomUUID(), "Acme", "900000"), placement(UUID.randomUUID(), "Acme", null),
            placement(UUID.randomUUID(), "Globex", "1200000"));

        var r = PlacementStatistics.compute(placements, Map.of(CS, 10), Map.of());

        assertThat(r.packages().counted()).isEqualTo(3);
        assertThat(r.packages().averageLpa()).isEqualByComparingTo("9");
        assertThat(r.packages().highestLpa()).isEqualByComparingTo("12");
        assertThat(r.packages().lowestLpa()).isEqualByComparingTo("6");
    }

    @Test
    void recruitersAreRankedByPlacementsThenNameAndLimitedToFive() {
        var placements = new ArrayList<PlacementRecord>();
        for (int i = 0; i < 3; i++) {
            placements.add(placement(UUID.randomUUID(), "  Acme ", "600000"));
        }
        for (int i = 0; i < 2; i++) {
            placements.add(placement(UUID.randomUUID(), "Globex", "800000"));
        }
        for (String name : List.of("Zeta", "Beta", "Alpha", "Gamma")) {
            placements.add(placement(UUID.randomUUID(), name, null));
        }

        var top = PlacementStatistics.compute(placements, Map.of(), Map.of()).topRecruiters();

        assertThat(top).extracting(PlacementStatistics.Recruiter::employer).containsExactly("Acme", "Globex", "Alpha", "Beta", "Gamma");
        assertThat(top.get(0).placements()).isEqualTo(3);
        assertThat(top.get(0).averageLpa()).isEqualByComparingTo("6");
        assertThat(top.get(2).averageLpa()).isNull();
    }
}
