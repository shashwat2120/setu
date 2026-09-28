package com.setu.learning.domain;

/**
 * No docs/state-machines.md entry exists for learning_progress either —
 * reuses the {@code NOT_STARTED}/{@code IN_PROGRESS}/{@code COMPLETED}
 * vocabulary the canonical docs already use repeatedly elsewhere (Assessment
 * Attempt, Intervention), rather than inventing a fresh one, disclosed in
 * the Day 10 report.
 */
public enum LearningProgressState {
    NOT_STARTED, IN_PROGRESS, COMPLETED
}
