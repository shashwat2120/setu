package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps career.applications (V46) — {@code (job_id, student_id)} unique, preventing a duplicate application at the database layer. See {@link ApplicationStatus}'s javadoc for why the transition set below is this module's own minimal, disclosed design (no canonical state-machines.md entry exists for this entity). */
@Entity
@Table(name = "applications", schema = "career")
public class Application {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    private String notes;

    protected Application() {
    }

    public Application(UUID id, UUID tenantId, UUID jobId, UUID studentId, Instant appliedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.jobId = jobId;
        this.studentId = studentId;
        this.status = ApplicationStatus.SUBMITTED;
        this.appliedAt = appliedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID jobId() {
        return jobId;
    }

    public UUID studentId() {
        return studentId;
    }

    public ApplicationStatus status() {
        return status;
    }

    public Instant appliedAt() {
        return appliedAt;
    }

    public String notes() {
        return notes;
    }

    public boolean canTransitionTo(ApplicationStatus target) {
        return switch (status) {
            case SUBMITTED -> target == ApplicationStatus.SHORTLISTED || target == ApplicationStatus.REJECTED
                || target == ApplicationStatus.WITHDRAWN;
            case SHORTLISTED -> target == ApplicationStatus.SELECTED || target == ApplicationStatus.REJECTED
                || target == ApplicationStatus.WITHDRAWN;
            case SELECTED, REJECTED, WITHDRAWN -> false;
        };
    }
}
