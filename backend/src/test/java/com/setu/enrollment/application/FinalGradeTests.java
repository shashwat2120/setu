package com.setu.enrollment.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a final grade means: the stored value, the pass/fail verdict, and the ordering a prerequisite's minimum grade is checked against. */
class FinalGradeTests {

    @Test
    void theStoredGradeIsTheCodeTrimmedAndUpperCase() {
        assertThat(FinalGrade.normalize(" b+ ")).isEqualTo("B+");
        assertThat(FinalGrade.normalize("A")).isEqualTo("A");
    }

    @Test
    void aFinalGradeNeedsACode() {
        assertThatThrownBy(() -> FinalGrade.normalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FinalGrade.normalize("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAnFIsAFail() {
        for (String pass : new String[] {"A", "B+", "c", "D", "P"}) {
            assertThat(FinalGrade.isPass(pass)).as(pass).isTrue();
        }
        assertThat(FinalGrade.isPass("F")).isFalse();
        assertThat(FinalGrade.isPass("f")).isFalse();
        assertThat(FinalGrade.isPass(null)).as("no grade is not a pass").isFalse();
        assertThat(FinalGrade.isPass(" ")).isFalse();
    }

    @Test
    void aSignDoesNotChangeTheLetter() {
        assertThat(FinalGrade.letter("B+")).isEqualTo("B");
        assertThat(FinalGrade.letter("a-")).isEqualTo("A");
        assertThat(FinalGrade.letter("P")).as("not on the scale").isNull();
    }

    @Test
    void aMinimumGradeIsMetByTheSameLetterOrBetter() {
        assertThat(FinalGrade.atLeast("A", "B")).isTrue();
        assertThat(FinalGrade.atLeast("B", "B")).isTrue();
        assertThat(FinalGrade.atLeast("B+", "B")).as("a sign is ignored").isTrue();
        assertThat(FinalGrade.atLeast("C", "B")).isFalse();
        assertThat(FinalGrade.atLeast("F", "D")).isFalse();
        assertThat(FinalGrade.atLeast("F", "F")).as("an F is at least an F, which is why a prerequisite with a minimum of F means nothing").isTrue();
    }

    @Test
    void aGradeOffTheScaleNeverMeetsAMinimum() {
        assertThat(FinalGrade.atLeast("P", "D")).isFalse();
        assertThat(FinalGrade.atLeast(null, "D")).isFalse();
        assertThat(FinalGrade.atLeast("A", "X")).isFalse();
    }
}
