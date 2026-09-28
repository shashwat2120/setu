package com.setu.directory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps directory.student_profiles (V14). Transitions per
 * docs/state-machines.md #7 — created directly into ACTIVE (no pending
 * "applicant" stage in v1; that's the {@code StudentAdmitted} transition),
 * ACTIVE&lt;-&gt;ON_LEAVE, ACTIVE&lt;-&gt;SUSPENDED, ACTIVE-&gt;GRADUATED,
 * ACTIVE|ON_LEAVE|SUSPENDED-&gt;WITHDRAWN (terminal). The exceptional
 * SUPER_ADMIN correction-of-a-terminal-status path described in the state
 * machine's "Correction" note is out of Day 6 scope — not a routine
 * transition, not exercised by Day 6's required test matrix.
 */
@Entity
@Table(name = "student_profiles", schema = "directory")
public class StudentProfile {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "admission_no", nullable = false)
    private String admissionNo;

    @Column(name = "roll_no")
    private String rollNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StudentStatus status;

    @Column(name = "admission_date", nullable = false)
    private LocalDate admissionDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Version
    private int version;

    protected StudentProfile() {
    }

    /** The StudentAdmitted transition ((none) -> ACTIVE) — there is no other constructor because there is no pre-ACTIVE state to construct into. */
    public StudentProfile(UUID id, UUID tenantId, UUID personId, String admissionNo, String rollNo,
                           LocalDate admissionDate, UUID actorUserId, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.personId = personId;
        this.admissionNo = admissionNo;
        this.rollNo = rollNo;
        this.status = StudentStatus.ACTIVE;
        this.admissionDate = admissionDate;
        this.createdAt = now;
        this.createdBy = actorUserId;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID personId() {
        return personId;
    }

    public String admissionNo() {
        return admissionNo;
    }

    public StudentStatus status() {
        return status;
    }

    public boolean canTransitionTo(StudentStatus target) {
        return switch (status) {
            case ACTIVE -> target == StudentStatus.ON_LEAVE || target == StudentStatus.SUSPENDED
                || target == StudentStatus.GRADUATED || target == StudentStatus.WITHDRAWN;
            case ON_LEAVE -> target == StudentStatus.ACTIVE || target == StudentStatus.WITHDRAWN;
            case SUSPENDED -> target == StudentStatus.ACTIVE || target == StudentStatus.WITHDRAWN;
            case GRADUATED, WITHDRAWN -> false;
        };
    }

    public void transitionTo(StudentStatus target, UUID actorUserId, Instant now) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition student from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
