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
 * Maps directory.faculty_profiles (V14). No docs/state-machines.md entry
 * exists for Faculty (see FacultyStatus's javadoc) — transitions here mirror
 * Student's ACTIVE/ON_LEAVE/SUSPENDED shape, with TERMINATED standing in for
 * WITHDRAWN as the terminal state, since the two vocabularies are otherwise
 * identical.
 */
@Entity
@Table(name = "faculty_profiles", schema = "directory")
public class FacultyProfile {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "employee_no", nullable = false)
    private String employeeNo;

    private String designation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FacultyStatus status;

    @Column(name = "joined_at", nullable = false)
    private LocalDate joinedAt;

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

    protected FacultyProfile() {
    }

    public FacultyProfile(UUID id, UUID tenantId, UUID personId, String employeeNo, String designation,
                           LocalDate joinedAt, UUID actorUserId, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.personId = personId;
        this.employeeNo = employeeNo;
        this.designation = designation;
        this.status = FacultyStatus.ACTIVE;
        this.joinedAt = joinedAt;
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

    public String employeeNo() {
        return employeeNo;
    }

    public FacultyStatus status() {
        return status;
    }

    public boolean canTransitionTo(FacultyStatus target) {
        return switch (status) {
            case ACTIVE -> target == FacultyStatus.ON_LEAVE || target == FacultyStatus.SUSPENDED
                || target == FacultyStatus.TERMINATED;
            case ON_LEAVE -> target == FacultyStatus.ACTIVE || target == FacultyStatus.TERMINATED;
            case SUSPENDED -> target == FacultyStatus.ACTIVE || target == FacultyStatus.TERMINATED;
            case TERMINATED -> false;
        };
    }

    public void transitionTo(FacultyStatus target, UUID actorUserId, Instant now) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition faculty from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
