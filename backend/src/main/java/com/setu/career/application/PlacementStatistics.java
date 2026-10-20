package com.setu.career.application;

import com.setu.career.domain.PlacementRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The placement figures for a college, calculated from its placement records and the sizes of its program cohorts: how many students are placed, in which programs, at what
 * package, with which employers. Pure arithmetic on what it is given. A package is only counted where one was recorded (a placement with no package is a placement, not a
 * package of zero), and a percentage is only given where there are students to take it of.
 *
 * <p>Percentages are of students actively in a program: a placed student who is in no active program (graduated, say) still counts as placed and has a placement, but is not in
 * any program's percentage, because there is no cohort to count them against.
 */
public final class PlacementStatistics {

    private static final BigDecimal LAKH = BigDecimal.valueOf(100000);
    static final int TOP_RECRUITERS = 5;

    /** Package figures in lakhs per annum, over the placements that state one ({@code counted} of them). */
    public record Packages(int counted, BigDecimal averageLpa, BigDecimal highestLpa, BigDecimal lowestLpa) {
    }

    public record ProgramRow(UUID programId, int students, int placed, Double placedPercentage) {
    }

    public record Recruiter(String employer, int placements, BigDecimal averageLpa) {
    }

    /** Totals across the college. {@code placedPercentage} is null when no student is in an active program. */
    public record Result(int activeStudents, int placedStudents, int placements, int placedInCohorts, Double placedPercentage, Packages packages, List<ProgramRow> byProgram,
                         List<Recruiter> topRecruiters) {
    }

    private PlacementStatistics() {
    }

    /**
     * @param placements       every placement record of the college
     * @param cohortSizes      students actively in each program
     * @param programOfStudent the active program of each placed student that has one
     */
    public static Result compute(List<PlacementRecord> placements, Map<UUID, Integer> cohortSizes, Map<UUID, UUID> programOfStudent) {
        Set<UUID> placedStudents = new HashSet<>();
        for (PlacementRecord p : placements) {
            placedStudents.add(p.studentId());
        }

        Map<UUID, Integer> placedByProgram = new HashMap<>();
        for (UUID student : placedStudents) {
            UUID program = programOfStudent.get(student);
            if (program != null) {
                placedByProgram.merge(program, 1, Integer::sum);
            }
        }
        List<ProgramRow> rows = new ArrayList<>();
        int activeStudents = 0;
        int placedInCohorts = 0;
        for (var entry : cohortSizes.entrySet()) {
            int size = entry.getValue();
            // a placed student counted for a program cannot exceed that program's students (records can lag the cohort)
            int placed = Math.min(placedByProgram.getOrDefault(entry.getKey(), 0), size);
            activeStudents += size;
            placedInCohorts += placed;
            rows.add(new ProgramRow(entry.getKey(), size, placed, percentage(placed, size)));
        }
        rows.sort(Comparator.comparing(ProgramRow::students).reversed().thenComparing(r -> r.programId().toString()));

        return new Result(activeStudents, placedStudents.size(), placements.size(), placedInCohorts, percentage(placedInCohorts, activeStudents), packages(placements), rows,
            recruiters(placements));
    }

    private static Double percentage(int part, int whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static BigDecimal lpa(BigDecimal ctc) {
        return ctc.divide(LAKH, 2, RoundingMode.HALF_UP);
    }

    private static Packages packages(List<PlacementRecord> placements) {
        List<BigDecimal> stated = placements.stream().map(PlacementRecord::ctc).filter(c -> c != null).toList();
        if (stated.isEmpty()) {
            return new Packages(0, null, null, null);
        }
        BigDecimal sum = stated.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Packages(stated.size(), lpa(sum.divide(BigDecimal.valueOf(stated.size()), 2, RoundingMode.HALF_UP)),
            lpa(stated.stream().max(Comparator.naturalOrder()).orElseThrow()), lpa(stated.stream().min(Comparator.naturalOrder()).orElseThrow()));
    }

    private static List<Recruiter> recruiters(List<PlacementRecord> placements) {
        Map<String, List<PlacementRecord>> byEmployer = new HashMap<>();
        for (PlacementRecord p : placements) {
            byEmployer.computeIfAbsent(p.employer().strip(), k -> new ArrayList<>()).add(p);
        }
        return byEmployer.entrySet().stream().map(e -> {
            List<BigDecimal> stated = e.getValue().stream().map(PlacementRecord::ctc).filter(c -> c != null).toList();
            BigDecimal average = stated.isEmpty() ? null
                : lpa(stated.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(stated.size()), 2, RoundingMode.HALF_UP));
            return new Recruiter(e.getKey(), e.getValue().size(), average);
        }).sorted(Comparator.comparing(Recruiter::placements).reversed().thenComparing(Recruiter::employer)).limit(TOP_RECRUITERS).toList();
    }
}
