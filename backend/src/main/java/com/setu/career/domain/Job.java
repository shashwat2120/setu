package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/** Maps career.jobs (V46) — a placement opportunity posted by a placement officer, scoped to one tenant/college. Not a general public job marketplace: visible only within the owning tenant, per Day 12's explicit scope rule. */
@Entity
@Table(name = "jobs", schema = "career")
public class Job {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "posted_by", nullable = false)
    private UUID postedBy;

    @Column(nullable = false)
    private String title;

    private String employer;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Version
    private int version;

    protected Job() {
    }

    public Job(UUID id, UUID tenantId, UUID postedBy, String title, String employer, String description) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.postedBy = postedBy;
        this.title = title;
        this.employer = employer;
        this.description = description;
        this.status = JobStatus.OPEN;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID postedBy() {
        return postedBy;
    }

    public String title() {
        return title;
    }

    public String employer() {
        return employer;
    }

    public String description() {
        return description;
    }

    public JobStatus status() {
        return status;
    }

    public int version() {
        return version;
    }

    public void update(String title, String employer, String description) {
        if (status != JobStatus.OPEN) {
            throw new IllegalStateException("Only open jobs can be edited");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = title.trim();
        this.employer = employer == null ? null : employer.trim();
        this.description = description == null ? null : description.trim();
    }

    public boolean canTransitionTo(JobStatus target) {
        return status == JobStatus.OPEN && target == JobStatus.CLOSED;
    }
}
