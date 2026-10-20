package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    @Column(name = "min_cgpa")
    private BigDecimal minCgpa;

    @Column(name = "min_attendance_pct")
    private BigDecimal minAttendancePct;

    @Column(name = "application_deadline")
    private Instant applicationDeadline;

    @Column(name = "package_lpa")
    private BigDecimal packageLpa;

    private String location;

    private Integer openings;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type")
    private JobType jobType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_program_ids", columnDefinition = "uuid[]")
    private List<UUID> allowedProgramIds;

    /** The graduating batch this job is for (the year students are expected to graduate), or null for any. */
    @Column(name = "graduating_year")
    private Integer graduatingYear;

    /** Whether applying needs the student to have a resume. */
    @Column(name = "requires_resume", nullable = false)
    private boolean requiresResume;

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

    /** What the officer said about who may apply and what the job is (see {@link JobTerms}); {@link JobTerms#NONE} when nothing was said. */
    public JobTerms terms() {
        return new JobTerms(minCgpa, minAttendancePct, applicationDeadline, packageLpa, location, openings, jobType, allowedProgramIds, graduatingYear, requiresResume);
    }

    /**
     * Sets the terms. A deadline must be in the future when it is set or changed (a deadline already passed that is simply left as it is, on a job
     * being edited for another reason, is not refused). Throws {@link IllegalArgumentException} for a value that makes no sense.
     */
    public void applyTerms(JobTerms terms, Instant now) {
        JobTerms t = terms == null ? JobTerms.NONE : terms;
        t.validate();
        if (t.applicationDeadline() != null && !t.applicationDeadline().equals(this.applicationDeadline) && !t.applicationDeadline().isAfter(now)) {
            throw new IllegalArgumentException("the application deadline must be in the future");
        }
        this.minCgpa = t.minCgpa();
        this.minAttendancePct = t.minAttendancePct();
        this.applicationDeadline = t.applicationDeadline();
        this.packageLpa = t.packageLpa();
        this.location = t.location();
        this.openings = t.openings();
        this.jobType = t.jobType();
        this.allowedProgramIds = t.allowedProgramIds();
        this.graduatingYear = t.graduatingYear();
        this.requiresResume = t.requiresResume();
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
