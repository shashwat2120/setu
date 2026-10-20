package com.setu.shared.career;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * What Career needs to know about a student to decide whether they meet a job's academic rules: their CGPA, their attendance across the sections they are in, and
 * their program. Those live in three other modules (Record, Attendance, Enrollment), and Career reaching into each would couple it to all of them, so it asks this
 * port instead; the module that already reads all of them (AI facts) answers it. The answer is for a decision the caller has already authorized (a student applying
 * for themselves, an officer previewing), so it carries no authorization of its own.
 *
 * <p>Every figure is null when it cannot be known yet (no published grade, no session held), which a rule treats as "cannot be shown to meet it", never as zero or 100.
 */
public interface StudentStandings {

    /**
     * @param cgpa                 the credit-weighted grade-point average to date, on the college's scale; null if nothing is graded yet
     * @param attendancePercentage present or late over sessions held, across the sections the student is enrolled in, 0 to 100; null if no session has been held
     * @param programId            the student's active program, or null if they have none
     */
    record Standing(BigDecimal cgpa, Double attendancePercentage, UUID programId) {
    }

    Optional<Standing> of(UUID studentId);
}
