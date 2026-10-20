package com.setu.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps enrollment.student_programs (V31) exactly. This is the ONLY entity
 * built in the {@code enrollment} module for Day 6 — enrollment.enrollments,
 * course_sections, waitlists etc. are explicitly out of scope per Day 6 §6.
 * <p>
 * No {@code version}/audit columns exist on this table (unlike almost every
 * other tenant-owned table in the schema) — this is a disclosed, deliberate
 * design read directly from V31, not an oversight: docs/state-machines.md
 * #8's Correction note says a new student_programs row is created for a
 * genuine re-admission rather than reopening a closed one. History is
 * preserved by NEVER mutating a row once its terminal state is reached, and
 * by inserting a new row for a fresh admission rather than reusing/updating
 * an old one — this append-only-at-the-terminal-boundary pattern is what
 * stands in for optimistic locking here, not a missing capability.
 */
@Entity
@Table(name = "student_programs", schema = "enrollment")
public class StudentProgram {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(name = "curriculum_id", nullable = false)
    private UUID curriculumId;

    @Column(name = "admission_year", nullable = false)
    private int admissionYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StudentProgramStatus status;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "starts_at", nullable = false)
    private LocalDate startsAt;

    @Column(name = "ends_at")
    private LocalDate endsAt;

    protected StudentProgram() {
    }

    public StudentProgram(UUID id, UUID tenantId, UUID studentId, UUID programId, UUID curriculumId,
                           int admissionYear, boolean primary, LocalDate startsAt, LocalDate endsAt) {
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("ends_at cannot precede starts_at");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.programId = programId;
        this.curriculumId = curriculumId;
        this.admissionYear = admissionYear;
        this.status = StudentProgramStatus.ACTIVE;
        this.primary = primary;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID studentId() {
        return studentId;
    }

    public int admissionYear() {
        return admissionYear;
    }

    public UUID programId() {
        return programId;
    }

    public UUID curriculumId() {
        return curriculumId;
    }

    public StudentProgramStatus status() {
        return status;
    }

    public boolean isPrimary() {
        return primary;
    }

    /** docs/state-machines.md #8: ACTIVE<->ON_HOLD; ACTIVE->COMPLETED; ACTIVE|ON_HOLD->DISCONTINUED. COMPLETED/DISCONTINUED are terminal — no in-place correction, only a new row (see class javadoc). */
    public boolean canTransitionTo(StudentProgramStatus target) {
        return switch (status) {
            case ACTIVE -> target == StudentProgramStatus.ON_HOLD || target == StudentProgramStatus.COMPLETED
                || target == StudentProgramStatus.DISCONTINUED;
            case ON_HOLD -> target == StudentProgramStatus.ACTIVE || target == StudentProgramStatus.DISCONTINUED;
            case COMPLETED, DISCONTINUED -> false;
        };
    }

    public void transitionTo(StudentProgramStatus target, LocalDate now) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition student program from " + status + " to " + target);
        }
        this.status = target;
        if (target == StudentProgramStatus.COMPLETED || target == StudentProgramStatus.DISCONTINUED) {
            this.endsAt = now;
        }
    }
}
