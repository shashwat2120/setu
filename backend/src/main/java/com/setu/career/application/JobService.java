package com.setu.career.application;

import com.setu.career.domain.Job;
import com.setu.career.domain.JobStatus;
import com.setu.career.infrastructure.JobRepository;
import com.setu.institution.application.CollegeService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Day 12 §Career: jobs — placement opportunities, scoped to one tenant/college (never a general public job marketplace). Placement-officer/admin managed (MANAGE_CAREER_LISTINGS); readable by anyone with VIEW_CAREER_LISTINGS. */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class JobService {

    private static final String MANAGE_PERMISSION = "MANAGE_CAREER_LISTINGS";
    private static final String VIEW_PERMISSION = "VIEW_CAREER_LISTINGS";

    private final JobRepository jobRepository;
    private final CollegeService collegeService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public JobService(JobRepository jobRepository, CollegeService collegeService, AuthorizationContext authorizationContext,
                       AuditLogWriter auditLogWriter) {
        this.jobRepository = jobRepository;
        this.collegeService = collegeService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID jobId) implements CreateResult {
        }

        record TenantNotFound() implements CreateResult {
        }

        record InvalidData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID tenantId, String title, String employer, String description) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("job", UUID.randomUUID(), tenantId));
        if (!collegeService.existsById(tenantId)) {
            return new CreateResult.TenantNotFound();
        }
        UUID actor = authorizationContext.currentUserId();
        Job job;
        try {
            job = new Job(UUID.randomUUID(), tenantId, actor, title, employer, description);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidData(e.getMessage());
        }
        jobRepository.saveAndFlush(job);
        auditLogWriter.write(tenantId, actor, "JOB_CREATED", "career.jobs", job.id(), Map.of("title", title));
        return new CreateResult.Success(job.id());
    }

    /** No independent authorization — reads are gated by {@link #get}/{@link #listForTenant}; this is the raw lookup other services in this module use internally. */
    Optional<Job> findById(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    /**
     * Day 16 §placement: real gap found live — PLACEMENT_OFFICER's role_permissions (V55) grant
     * MANAGE_CAREER_LISTINGS but never VIEW_CAREER_LISTINGS, so a placement officer could create and close jobs
     * yet not list or view the very jobs they manage. MANAGE implies VIEW here (never the other way round).
     */
    private void assertViewable(UUID resourceId, UUID tenantId) {
        if (!authorizationContext.can(VIEW_PERMISSION, ResourceRef.of("job", resourceId, tenantId)).isAllowed()) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("job", resourceId, tenantId));
        }
    }

    @Transactional(readOnly = true)
    public Optional<Job> get(UUID jobId) {
        Optional<Job> job = jobRepository.findById(jobId);
        job.ifPresent(j -> assertViewable(j.id(), j.tenantId()));
        return job;
    }

    @Transactional(readOnly = true)
    public List<Job> listForTenant(UUID tenantId) {
        assertViewable(UUID.randomUUID(), tenantId);
        return jobRepository.findByTenantId(tenantId);
    }

    public sealed interface CloseResult {
        record Success() implements CloseResult {
        }

        record NotFound() implements CloseResult {
        }

        record InvalidTransition() implements CloseResult {
        }
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {}
        record NotFound() implements UpdateResult {}
        record InvalidData(String message) implements UpdateResult {}
        record InvalidTransition() implements UpdateResult {}
        record VersionConflict() implements UpdateResult {}
    }

    @Transactional
    public UpdateResult update(UUID jobId, String title, String employer, String description, int expectedVersion) {
        Optional<Job> found = jobRepository.findById(jobId);
        if (found.isEmpty()) return new UpdateResult.NotFound();
        Job job = found.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("job", job.id(), job.tenantId()));
        if (job.version() != expectedVersion) return new UpdateResult.VersionConflict();
        try {
            job.update(title, employer, description);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidData(e.getMessage());
        } catch (IllegalStateException e) {
            return new UpdateResult.InvalidTransition();
        }
        jobRepository.flush();
        auditLogWriter.write(job.tenantId(), authorizationContext.currentUserId(), "JOB_UPDATED", "career.jobs", job.id(), Map.of("title", job.title()));
        return new UpdateResult.Success();
    }

    @Transactional
    public CloseResult close(UUID jobId) {
        Optional<Job> maybeJob = jobRepository.findById(jobId);
        if (maybeJob.isEmpty()) {
            return new CloseResult.NotFound();
        }
        Job job = maybeJob.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("job", job.id(), job.tenantId()));
        if (!job.canTransitionTo(JobStatus.CLOSED)) {
            return new CloseResult.InvalidTransition();
        }
        int updated = jobRepository.closeIfOpen(jobId);
        if (updated == 0) {
            return new CloseResult.InvalidTransition();
        }
        auditLogWriter.write(job.tenantId(), authorizationContext.currentUserId(), "JOB_CLOSED", "career.jobs", job.id(), Map.of());
        return new CloseResult.Success();
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID tenantId, String title, String employer, String description) {
        return switch (create(tenantId, title, employer, description)) {
            case CreateResult.Success s -> s.jobId();
            default -> throw new IllegalStateException("seed job creation failed: " + title);
        };
    }

}
