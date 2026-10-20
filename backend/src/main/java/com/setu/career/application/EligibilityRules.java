package com.setu.career.application;

import com.setu.career.domain.JobTerms;
import com.setu.shared.career.StudentStandings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which of a job's academic rules a student does not meet, each with a sentence that says what the job needs and what the student has. Pure arithmetic on the job's
 * terms and the student's standing: the same inputs always give the same answer, and a student is never refused without being told exactly why.
 *
 * <p>A figure that cannot be known yet (no published grade, no session held) does not meet a rule that asks for it: the system does not guess in the student's favour.
 */
final class EligibilityRules {

    /** One rule the student does not meet. {@code code} is stable for callers; {@code message} is for the student. */
    record Unmet(String code, String message) {
    }

    static final String DEADLINE_PASSED = "DEADLINE_PASSED";
    static final String MIN_CGPA = "MIN_CGPA";
    static final String MIN_ATTENDANCE = "MIN_ATTENDANCE";
    static final String PROGRAM_NOT_ALLOWED = "PROGRAM_NOT_ALLOWED";
    static final String GRADUATING_YEAR = "GRADUATING_YEAR";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private EligibilityRules() {
    }

    /** @param standing null if nothing could be learned about the student (they are then taken not to meet any rule that needs it) */
    static List<Unmet> unmet(JobTerms terms, Instant now, StudentStandings.Standing standing) {
        List<Unmet> out = new ArrayList<>();
        if (terms.applicationDeadline() != null && !now.isBefore(terms.applicationDeadline())) {
            out.add(new Unmet(DEADLINE_PASSED, "The application deadline passed on "
                + DATE.format(terms.applicationDeadline().atZone(ZoneId.of("Asia/Kolkata"))) + " (IST)."));
        }
        if (terms.minCgpa() != null) {
            BigDecimal cgpa = standing == null ? null : standing.cgpa();
            if (cgpa == null) {
                out.add(new Unmet(MIN_CGPA, "Needs a CGPA of at least " + plain(terms.minCgpa()) + "; you have no published grades yet."));
            } else if (cgpa.compareTo(terms.minCgpa()) < 0) {
                out.add(new Unmet(MIN_CGPA, "Needs a CGPA of at least " + plain(terms.minCgpa()) + "; yours is " + plain(cgpa) + "."));
            }
        }
        if (terms.minAttendancePct() != null) {
            Double attendance = standing == null ? null : standing.attendancePercentage();
            if (attendance == null) {
                out.add(new Unmet(MIN_ATTENDANCE, "Needs attendance of at least " + plain(terms.minAttendancePct()) + "%; no class session has been held for you yet."));
            } else if (BigDecimal.valueOf(attendance).compareTo(terms.minAttendancePct()) < 0) {
                out.add(new Unmet(MIN_ATTENDANCE, "Needs attendance of at least " + plain(terms.minAttendancePct()) + "%; yours is "
                    + plain(BigDecimal.valueOf(attendance)) + "%."));
            }
        }
        if (terms.allowedProgramIds() != null) {
            if (standing == null || standing.programId() == null || !terms.allowedProgramIds().contains(standing.programId())) {
                out.add(new Unmet(PROGRAM_NOT_ALLOWED, "This job is open only to students of certain programs, and yours is not one of them."));
            }
        }
        if (terms.graduatingYear() != null) {
            Integer year = standing == null ? null : standing.expectedGraduationYear();
            if (year == null) {
                out.add(new Unmet(GRADUATING_YEAR, "This job is for the batch of " + terms.graduatingYear() + "; your record does not show when you are expected to graduate."));
            } else if (!year.equals(terms.graduatingYear())) {
                out.add(new Unmet(GRADUATING_YEAR, "This job is for the batch of " + terms.graduatingYear() + "; yours is " + year + "."));
            }
        }
        return out;
    }

    /** A figure as a person reads it: 7, 7.5, 7.25, never 7.500000. */
    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
