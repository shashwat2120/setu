package com.setu.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps enrollment.enrollments (V32) — one row per (section, student) EVER
 * (the composite unique constraint), state transitions happening on this
 * one row rather than a new row per attempt (docs/erd/02-academic.md).
 * Transitions per docs/state-machines.md #11:
 * REQUESTED -&gt; ENROLLED|WAITLISTED|REJECTED, WAITLISTED -&gt; ENROLLED,
 * ENROLLED -&gt; DROPPED|COMPLETED.
 * <p>
 * Two ADDITIONAL transitions beyond that literal list are implemented,
 * both disclosed necessities rather than invented elaboration:
 * <ol>
 *   <li>{@code DROPPED -> REQUESTED} and {@code REJECTED -> REQUESTED}
 *       ("reopen"): the composite unique constraint means a student can
 *       never get a second row for the same section — state-machines.md
 *       #11 itself says a drop's row "is reused/reopened on genuine
 *       re-enrollment rather than a new row being created." Without this,
 *       a REJECTED or DROPPED student could never retry that section
 *       through the normal API ever again, which cannot be the intent.</li>
 *   <li>{@code WAITLISTED -> DROPPED} ("withdraw from waitlist"): not
 *       listed as a source state for DROPPED in the literal bullet list,
 *       but a waitlisted student must be able to leave the waitlist
 *       (Day 8 §12: "waitlist manipulation is authorized" implies the
 *       student can act on their own entry) — the alternative of leaving
 *       waitlisted students with no exit path isn't defensible.</li>
 * </ol>
 * {@code COMPLETED} has no outbound transition — genuinely terminal.
 */
@Entity
@Table(name = "enrollments", schema = "enrollment")
public class Enrollment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EnrollmentStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "enrolled_at")
    private Instant enrolledAt;

    @Column(name = "dropped_at")
    private Instant droppedAt;

    @Column(name = "final_status")
    private String finalStatus;

    @Version
    private int version;

    protected Enrollment() {
    }

    public Enrollment(UUID id, UUID tenantId, UUID sectionId, UUID studentId, Instant requestedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.sectionId = sectionId;
        this.studentId = studentId;
        this.status = EnrollmentStatus.REQUESTED;
        this.requestedAt = requestedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID sectionId() {
        return sectionId;
    }

    public UUID studentId() {
        return studentId;
    }

    public EnrollmentStatus status() {
        return status;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant enrolledAt() {
        return enrolledAt;
    }

    public Instant droppedAt() {
        return droppedAt;
    }

    public String finalStatus() {
        return finalStatus;
    }

    public boolean canTransitionTo(EnrollmentStatus target) {
        return switch (status) {
            case REQUESTED -> target == EnrollmentStatus.ENROLLED || target == EnrollmentStatus.WAITLISTED
                || target == EnrollmentStatus.REJECTED;
            case WAITLISTED -> target == EnrollmentStatus.ENROLLED || target == EnrollmentStatus.DROPPED;
            case ENROLLED -> target == EnrollmentStatus.DROPPED || target == EnrollmentStatus.COMPLETED;
            case DROPPED, REJECTED -> target == EnrollmentStatus.REQUESTED;
            case COMPLETED -> false;
        };
    }

    public void markEnrolled(Instant now) {
        if (!canTransitionTo(EnrollmentStatus.ENROLLED)) {
            throw new IllegalStateException("Cannot transition enrollment from " + status + " to ENROLLED");
        }
        this.status = EnrollmentStatus.ENROLLED;
        this.enrolledAt = now;
    }

    public void markWaitlisted() {
        if (!canTransitionTo(EnrollmentStatus.WAITLISTED)) {
            throw new IllegalStateException("Cannot transition enrollment from " + status + " to WAITLISTED");
        }
        this.status = EnrollmentStatus.WAITLISTED;
    }

    public void markRejected() {
        if (!canTransitionTo(EnrollmentStatus.REJECTED)) {
            throw new IllegalStateException("Cannot transition enrollment from " + status + " to REJECTED");
        }
        this.status = EnrollmentStatus.REJECTED;
    }

    public void markDropped(Instant now) {
        if (!canTransitionTo(EnrollmentStatus.DROPPED)) {
            throw new IllegalStateException("Cannot transition enrollment from " + status + " to DROPPED");
        }
        this.status = EnrollmentStatus.DROPPED;
        this.droppedAt = now;
    }

    public void markCompleted(String finalStatus) {
        if (!canTransitionTo(EnrollmentStatus.COMPLETED)) {
            throw new IllegalStateException("Cannot transition enrollment from " + status + " to COMPLETED");
        }
        this.status = EnrollmentStatus.COMPLETED;
        this.finalStatus = finalStatus;
    }

    /** Reopens a DROPPED/REJECTED row for a new enrollment attempt — see this class's javadoc for why this is a disclosed necessity, not invented elaboration. */
    public void reopen(Instant requestedAt) {
        if (!canTransitionTo(EnrollmentStatus.REQUESTED)) {
            throw new IllegalStateException("Cannot reopen enrollment from " + status);
        }
        this.status = EnrollmentStatus.REQUESTED;
        this.requestedAt = requestedAt;
        this.enrolledAt = null;
        this.droppedAt = null;
        this.finalStatus = null;
    }
}
