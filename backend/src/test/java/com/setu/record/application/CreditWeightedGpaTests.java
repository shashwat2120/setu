package com.setu.record.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The GPA rollup rule documented on {@link AcademicRecordService}: component weights -> course grade point -> credit-weighted GPA. */
class CreditWeightedGpaTests {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static AcademicRecordService.GradeLine grade(UUID component, String gradePoint) {
        return new AcademicRecordService.GradeLine(component, bd("10"), "X", gradePoint == null ? null : bd(gradePoint));
    }

    private static AcademicRecordService.TranscriptLine line(String credits, String courseGradePoint) {
        return new AcademicRecordService.TranscriptLine(UUID.randomUUID(), "C", "Course", credits == null ? null : bd(credits),
            "S1", "ENROLLED", List.of(), courseGradePoint == null ? null : bd(courseGradePoint));
    }

    @Test
    void courseGradePointIsTheWeightedAverageOfComponentGradePoints() {
        UUID quiz = UUID.randomUUID();
        UUID exam = UUID.randomUUID();
        // exam counts three times as much as the quiz: (4*1 + 8*3) / 4 = 7.00
        var result = AcademicRecordService.courseGradePoint(
            List.of(grade(quiz, "4"), grade(exam, "8")), Map.of(quiz, bd("0.25"), exam, bd("0.75")));

        assertThat(result).isEqualByComparingTo("7.00");
    }

    @Test
    void aMissingOrNonPositiveWeightCountsAsOne() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        var result = AcademicRecordService.courseGradePoint(List.of(grade(a, "6"), grade(b, "8")), Map.of(a, bd("0")));

        assertThat(result).isEqualByComparingTo("7.00");
    }

    @Test
    void componentsWithoutAGradePointAreIgnoredAndNothingPublishedGivesNull() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(AcademicRecordService.courseGradePoint(List.of(grade(a, null), grade(b, "9")), Map.of())).isEqualByComparingTo("9.00");
        assertThat(AcademicRecordService.courseGradePoint(List.of(grade(a, null)), Map.of())).isNull();
        assertThat(AcademicRecordService.courseGradePoint(List.of(), Map.of())).isNull();
    }

    @Test
    void gpaWeightsEachCourseByItsCredits() {
        // (10 * 4 + 6 * 2) / 6 = 8.67 — a plain average of the two courses would have been 8.00
        var gpa = AcademicRecordService.creditWeightedGpa(List.of(line("4", "10"), line("2", "6")));

        assertThat(gpa).isEqualByComparingTo("8.67");
    }

    @Test
    void ungradedCoursesAndCoursesWithoutCreditsAreLeftOutNotCountedAsZero() {
        var gpa = AcademicRecordService.creditWeightedGpa(
            List.of(line("4", "8"), line("4", null), line(null, "3"), line("0", "2")));

        assertThat(gpa).isEqualByComparingTo("8.00");
    }

    @Test
    void noGradedCoursesMeansNoGpaRatherThanZero() {
        assertThat(AcademicRecordService.creditWeightedGpa(List.of(line("4", null)))).isNull();
        assertThat(AcademicRecordService.creditWeightedGpa(List.of())).isNull();
    }
}
