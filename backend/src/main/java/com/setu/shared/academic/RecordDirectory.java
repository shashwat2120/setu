package com.setu.shared.academic;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the academic record (transcript, GPA, degree progress) needs to know about Academic's data, and nothing more: a student's
 * enrollments with the course each one is for, the curriculum the student is on, and the credits that curriculum requires. Each question is a
 * narrow lookup with no authorization of its own; the caller has already authorized the operation it is part of.
 * <p>
 * It is separate from {@link AcademicDirectory} on purpose: that port serves learning, assessment and attendance, none of which needs course
 * credits or curricula, and the record's questions are all reads of the same few facts, joined. In the monolith
 * {@code enrollment.application.RecordDirectoryAdapter} answers from Academic's own services; once the record runs inside the Assessment service
 * it is answered from that service's local copy, kept current by Academic's events.
 */
public interface RecordDirectory {

    /** The most enrollments one student's record lists; the record is a page of the student's history, not an export. */
    int MAX_ENROLLMENTS = 200;

    /**
     * One enrollment with the course it is for. The course fields are null when the section or course is not known; {@code status} is the
     * enrollment's status name (for example {@code ENROLLED}, {@code COMPLETED}, {@code DROPPED}).
     * <p>
     * {@code finalGrade} is the grade code the course ended with (null until its final grade is published). {@code passed} is Academic's verdict,
     * so that no consumer has to know the grade scale: the enrollment is COMPLETED and its final grade is not an F. Only a passed course earns its
     * credits.
     */
    record CourseEnrollment(UUID enrollmentId, String status, String sectionCode, String courseCode, String courseTitle, BigDecimal credits,
                            String finalGrade, boolean passed) {
    }

    /** Every enrollment of this student, whatever its status, up to {@link #MAX_ENROLLMENTS}. */
    List<CourseEnrollment> enrollmentsOf(UUID studentId);

    /** The curriculum the student is on right now, if they are in a program. */
    Optional<UUID> activeCurriculumOf(UUID studentId);

    /** The credits a curriculum requires in total (zero when it states no minimum). */
    BigDecimal requiredCredits(UUID curriculumId);
}
