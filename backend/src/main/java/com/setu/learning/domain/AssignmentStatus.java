package com.setu.learning.domain;

/**
 * docs/state-machines.md only documents "Assignment Submission" (#16), not
 * the assignment entity itself. Smallest defensible substitute, modeled on
 * the sibling Assessment entity's DRAFT-then-open-then-closed shape without
 * importing its full complexity (scheduling windows, published-immutability,
 * component weights — none of that is Day 10 scope): {@code DRAFT} (not yet
 * visible to students, matches the migration's own default), {@code
 * PUBLISHED} (visible, students may submit), {@code CLOSED} (no further
 * submissions — a faculty/admin action, not automatic on {@code due_at}
 * passing). Disclosed in the Day 10 report as an invented-but-minimal
 * lifecycle.
 */
public enum AssignmentStatus {
    DRAFT, PUBLISHED, CLOSED
}
