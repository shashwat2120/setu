package com.setu.assessment.domain;

/**
 * No canonical vocabulary is documented for {@code questions.type} — smallest
 * defensible, disclosed set with a genuine, deterministic evaluation rule for
 * each (see {@code AssessmentEvaluationService}'s javadoc for the exact
 * algorithm per type): {@code MCQ_SINGLE} and {@code TRUE_FALSE} are
 * exact-match auto-graded; {@code SHORT_ANSWER} is a normalized-string-match
 * auto-graded; {@code ESSAY} has no deterministic algorithm and is
 * deliberately left for manual evaluation — never an invented AI grader.
 */
public enum QuestionType {
    MCQ_SINGLE, TRUE_FALSE, SHORT_ANSWER, ESSAY
}
