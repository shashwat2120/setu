package com.setu.career.application;

import com.setu.career.domain.Job;
import com.setu.career.domain.JobSkill;
import com.setu.career.domain.ProficiencyLevel;
import com.setu.career.domain.Skill;
import com.setu.career.infrastructure.JobSkillInsertGuard;
import com.setu.career.infrastructure.JobSkillRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Day 12 §Career: job_skills — a job's required skills, together forming the deterministic eligibility criteria a student is checked against at application time (no separate "eligibility criteria" table exists in the canonical schema — see this class's own eligibility helper, used by ApplicationService). Placement-officer/admin managed (MANAGE_CAREER_LISTINGS). */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class JobSkillService {

    private static final String MANAGE_PERMISSION = "MANAGE_CAREER_LISTINGS";

    private final JobSkillRepository jobSkillRepository;
    private final JobSkillInsertGuard jobSkillInsertGuard;
    private final JobService jobService;
    private final SkillService skillService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public JobSkillService(JobSkillRepository jobSkillRepository, JobSkillInsertGuard jobSkillInsertGuard, JobService jobService,
                            SkillService skillService, AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.jobSkillRepository = jobSkillRepository;
        this.jobSkillInsertGuard = jobSkillInsertGuard;
        this.jobService = jobService;
        this.skillService = skillService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface AddResult {
        record Success(UUID jobSkillId) implements AddResult {
        }

        record JobNotFound() implements AddResult {
        }

        record SkillNotFound() implements AddResult {
        }

        record AlreadyRequired() implements AddResult {
        }
    }

    @Transactional
    public AddResult add(UUID jobId, UUID skillId, ProficiencyLevel requiredLevel) {
        Optional<Job> maybeJob = jobService.findById(jobId);
        if (maybeJob.isEmpty()) {
            return new AddResult.JobNotFound();
        }
        Job job = maybeJob.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("job_skill", UUID.randomUUID(), job.tenantId()));

        Optional<Skill> skill = skillService.findById(skillId);
        if (skill.isEmpty() || !skill.get().tenantId().equals(job.tenantId())) {
            return new AddResult.SkillNotFound();
        }
        JobSkill jobSkill = new JobSkill(UUID.randomUUID(), job.tenantId(), jobId, skillId, requiredLevel);
        try {
            jobSkillInsertGuard.insert(jobSkill);
        } catch (DataIntegrityViolationException e) {
            return new AddResult.AlreadyRequired();
        }
        auditLogWriter.write(job.tenantId(), authorizationContext.currentUserId(), "JOB_SKILL_ADDED",
            "career.job_skills", jobSkill.id(), Map.of("jobId", jobId, "skillId", skillId));
        return new AddResult.Success(jobSkill.id());
    }

    /**
     * Day 16 §placement: real gap found live — this javadoc used to claim "same visibility as the owning job",
     * but nothing ever actually called {@link JobService#get} to enforce that; the controller went straight to
     * this method, so any authenticated user of any role/tenant could list any job's skill requirements by ID
     * alone. Delegating to {@code jobService.get} first makes the claim true.
     */
    @Transactional(readOnly = true)
    public List<JobSkill> listForJob(UUID jobId) {
        jobService.get(jobId);
        return jobSkillRepository.findByJobId(jobId);
    }

    public sealed interface RemoveResult {
        record Success() implements RemoveResult {}
        record NotFound() implements RemoveResult {}
    }

    @Transactional
    public RemoveResult remove(UUID jobSkillId) {
        Optional<JobSkill> found = jobSkillRepository.findById(jobSkillId);
        if (found.isEmpty()) return new RemoveResult.NotFound();
        JobSkill jobSkill = found.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("job_skill", jobSkill.id(), jobSkill.tenantId()));
        jobSkillRepository.delete(jobSkill);
        auditLogWriter.write(jobSkill.tenantId(), authorizationContext.currentUserId(), "JOB_SKILL_REMOVED",
            "career.job_skills", jobSkill.id(), Map.of("jobId", jobSkill.jobId(), "skillId", jobSkill.skillId()));
        return new RemoveResult.Success();
    }
}
