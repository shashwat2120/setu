package com.setu.enrollment.application;

import java.util.List;
import java.util.Locale;

/**
 * What a course's final grade means, in one place: the value recorded as an enrollment's {@code final_status} when its final grade is published,
 * and the two questions asked of it afterwards (did the student pass; is it at least some minimum). The college grade scale is the letters
 * {@code A B C D F}, highest first, the same vocabulary a prerequisite's {@code minimum_grade} is written in. A grade code may carry a sign
 * ({@code B+}); only its letter counts here, while the code as published is what is stored.
 * <p>
 * Only an {@code F} is a fail. A code outside the scale (a pass/fail course's {@code P}, say) is a pass but never satisfies a minimum grade,
 * because it cannot be ranked.
 */
public final class FinalGrade {

    /** Lowest to highest. */
    private static final List<String> SCALE = List.of("F", "D", "C", "B", "A");

    private FinalGrade() {
    }

    /** The code as it is stored: trimmed and upper-case. @throws IllegalArgumentException if there is no code */
    public static String normalize(String gradeCode) {
        if (gradeCode == null || gradeCode.isBlank()) {
            throw new IllegalArgumentException("a final grade needs a grade code");
        }
        return gradeCode.trim().toUpperCase(Locale.ROOT);
    }

    /** The scale letter of a code ({@code B+} is {@code B}), or null if the code is not on the scale. */
    static String letter(String gradeCode) {
        if (gradeCode == null || gradeCode.isBlank()) {
            return null;
        }
        String first = gradeCode.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        return SCALE.contains(first) ? first : null;
    }

    /** Whether a final grade is a pass: any recorded grade except an F. */
    public static boolean isPass(String gradeCode) {
        return gradeCode != null && !gradeCode.isBlank() && !"F".equals(letter(gradeCode));
    }

    /** Whether {@code actual} is at least {@code minimum} on the scale; false if either is not on it. */
    static boolean atLeast(String actual, String minimum) {
        String actualLetter = letter(actual);
        String minimumLetter = letter(minimum);
        if (actualLetter == null || minimumLetter == null) {
            return false; // an immutable list does not even accept a question about null
        }
        return SCALE.indexOf(actualLetter) >= SCALE.indexOf(minimumLetter);
    }
}
