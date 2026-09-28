package com.setu.learning.domain;

/**
 * No docs/state-machines.md entry exists for course_modules — the smallest
 * defensible lifecycle implied by the migration's {@code DEFAULT 'ACTIVE'}
 * status column: a module is visible to enrolled students while
 * {@code ACTIVE}, and can be hidden (without deleting content or breaking
 * {@code lessons} FKs) by archiving it. Disclosed as an invented-but-minimal
 * lifecycle in the Day 10 report, not a documented state machine.
 */
public enum CourseModuleStatus {
    ACTIVE, ARCHIVED
}
