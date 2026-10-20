package com.setu.career.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a placement officer says about a job beyond its title, employer and description: who may apply (a minimum CGPA, a minimum attendance, the programs
 * it is open to, the graduating batch it is for, a deadline) and what it is (package in lakhs per annum, a place, the number of openings, a type). Every field is optional and a job with none
 * ({@link #NONE}) behaves as it always did. {@code minCgpa} is on the college's own grade-point scale; {@code minAttendancePct} is 0 to 100.
 *
 * <p>The eligibility fields are only ever checked by {@code ApplicationService}, which says which of them a student does not meet; {@code packageLpa},
 * {@code location}, {@code openings} and {@code jobType} are information for the student and the officer and are not enforced.
 */
public record JobTerms(BigDecimal minCgpa, BigDecimal minAttendancePct, Instant applicationDeadline, BigDecimal packageLpa, String location,
                       Integer openings, JobType jobType, List<UUID> allowedProgramIds, Integer graduatingYear) {

    public static final JobTerms NONE = new JobTerms(null, null, null, null, null, null, null, null, null);

    /** A job open to every batch. */
    public JobTerms(BigDecimal minCgpa, BigDecimal minAttendancePct, Instant applicationDeadline, BigDecimal packageLpa, String location,
                    Integer openings, JobType jobType, List<UUID> allowedProgramIds) {
        this(minCgpa, minAttendancePct, applicationDeadline, packageLpa, location, openings, jobType, allowedProgramIds, null);
    }

    public JobTerms {
        location = location == null || location.isBlank() ? null : location.trim();
        allowedProgramIds = allowedProgramIds == null || allowedProgramIds.isEmpty() ? null : allowedProgramIds.stream().distinct().toList();
    }

    /** The rules a student must meet, in the words an error message uses; throws {@link IllegalArgumentException} naming the first value that makes no sense. */
    public void validate() {
        if (minCgpa != null && (minCgpa.signum() < 0 || minCgpa.compareTo(BigDecimal.TEN) > 0)) {
            throw new IllegalArgumentException("the minimum CGPA must be between 0 and 10");
        }
        if (minAttendancePct != null && (minAttendancePct.signum() < 0 || minAttendancePct.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("the minimum attendance must be between 0 and 100 percent");
        }
        if (packageLpa != null && packageLpa.signum() < 0) {
            throw new IllegalArgumentException("the package cannot be negative");
        }
        if (graduatingYear != null && (graduatingYear < 2000 || graduatingYear > 2100)) {
            throw new IllegalArgumentException("the graduating batch must be a year between 2000 and 2100");
        }
        if (openings != null && openings < 1) {
            throw new IllegalArgumentException("a job needs at least one opening");
        }
    }

    /** Whether any rule that decides who may apply is set (the information-only fields do not count). */
    public boolean hasAcademicRules() {
        return minCgpa != null || minAttendancePct != null || allowedProgramIds != null || graduatingYear != null;
    }
}
