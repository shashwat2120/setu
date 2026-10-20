package com.setu.career.application;

import com.setu.career.domain.Application;
import com.setu.career.domain.ApplicationStatus;
import com.setu.career.domain.Job;
import com.setu.career.domain.JobSkill;
import com.setu.career.domain.ProficiencyLevel;
import com.setu.career.domain.StudentSkill;
import com.setu.career.infrastructure.ApplicationInsertGuard;
import com.setu.career.infrastructure.ApplicationRepository;
import com.setu.directory.application.StudentProfileService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.career.StudentStandings;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 12 §Career: applications — {@code (job_id, student_id)} unique at the
 * database layer (duplicate-application prevention, backed by {@link
 * ApplicationInsertGuard}). See {@link ApplicationStatus}'s javadoc for the
 * lifecycle design (no canonical state-machines.md entry exists).
 * <p>
 * <b>Eligibility</b> (Day 12's requirement that eligibility be
 * deterministic and explainable): no {@code eligibility_criteria} table
 * exists anywhere in the canonical schema — the ERD's own note for this
 * module says gap analysis is derived at query time rather than a
 * stored/maintained table.
 * {@link #checkEligibility} compares a job's required skills ({@link
 * JobSkill#requiredLevel}) against the student's own skills ({@link
 * StudentSkill#proficiencyLevel}) using {@link ProficiencyLevel}'s ordinal
 * scale — a student is eligible exactly when every required skill is
 * present at or above its required level; the result lists exactly which
 * skills are missing or insufficient, so a denial is always explainable,
 * never opaque. This check is a hard gate on {@link #apply} — an
 * ineligible student cannot submit an application at all.
 * <p>
 * {@code @NamedInterface} added Day 14: {@code aiexplain.application}'s
 * {@code getSkillGap} tool reuses {@link #previewEligibility} AS-IS — it
 * already has exactly the right self/admin authorization (career data has
 * no advisor or guardian access at all, per the authorization matrix), so
 * no new port or wrapper was needed.
 */
@org.springframework.modulith.NamedInterface
@Service
public class ApplicationService {

    private static final String APPLY_PERMISSION = "APPLY_TO_JOB";
    private static final String MANAGE_PERMISSION = "MANAGE_CAREER_LISTINGS";
    private static final String VIEW_PERMISSION = "VIEW_CAREER_LISTINGS";

    private final ApplicationRepository applicationRepository;
    private final ApplicationInsertGuard applicationInsertGuard;
    private final JobService jobService;
    private final JobSkillService jobSkillService;
    private final StudentSkillService studentSkillService;
    private final StudentProfileService studentProfileService;
    private final CareerAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final StudentStandings studentStandings;

    public ApplicationService(ApplicationRepository applicationRepository, ApplicationInsertGuard applicationInsertGuard,
                               JobService jobService, JobSkillService jobSkillService, StudentSkillService studentSkillService,
                               StudentProfileService studentProfileService, CareerAccessSupport accessSupport,
                               AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter, StudentStandings studentStandings) {
        this.studentStandings = studentStandings;
        this.applicationRepository = applicationRepository;
        this.applicationInsertGuard = applicationInsertGuard;
        this.jobService = jobService;
        this.jobSkillService = jobSkillService;
        this.studentSkillService = studentSkillService;
        this.studentProfileService = studentProfileService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    /**
     * {@code eligible} is true exactly when no required skill is missing and no academic rule (deadline, CGPA, attendance, program) is unmet. A student who is not eligible
     * is told which skills and which rules, never just "no".
     */
    public record EligibilityResult(boolean eligible, List<UUID> missingOrInsufficientSkillIds, List<UnmetRule> unmetRules) {
        public EligibilityResult(boolean eligible, List<UUID> missingOrInsufficientSkillIds) {
            this(eligible, missingOrInsufficientSkillIds, List.of());
        }
    }

    /** One academic rule a student does not meet: a stable {@code code} (DEADLINE_PASSED, MIN_CGPA, MIN_ATTENDANCE, PROGRAM_NOT_ALLOWED) and a sentence for the student. */
    public record UnmetRule(String code, String message) {
    }

    /** Public, authorization-checked entry point for a student (or admin/placement officer) to preview eligibility before applying. */
    @Transactional(readOnly = true)
    public Optional<EligibilityResult> previewEligibility(UUID jobId, UUID studentId) {
        Optional<Job> maybeJob = jobService.findById(jobId);
        if (maybeJob.isEmpty()) {
            return Optional.empty();
        }
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return Optional.empty();
        }
        UUID tenantId = maybeJob.get().tenantId();
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && !ownStudentId.get().equals(studentId)) {
            // a student previews their own eligibility and no one else's, whatever else their role may let them view
            throw new org.springframework.security.access.AccessDeniedException("permission_denied");
        }
        if (ownStudentId.isEmpty()) {
            // someone else's eligibility: a placement officer manages these jobs and their applicants, so managing implies viewing (as it does for the jobs themselves)
            var resource = ResourceRef.of("application", studentId, tenantId);
            if (!authorizationContext.can(VIEW_PERMISSION, resource).isAllowed()) {
                authorizationContext.assertCan(MANAGE_PERMISSION, resource);
            }
        }
        return Optional.of(checkEligibility(jobId, studentId));
    }

    /** Deterministic, explainable — see class javadoc. No authorization of its own; callers already authorize their own operation. */
    EligibilityResult checkEligibility(UUID jobId, UUID studentId) {
        List<JobSkill> required = jobSkillService.listForJob(jobId);
        List<StudentSkill> owned = studentSkillService.listForStudent(studentId);
        List<UUID> missing = new ArrayList<>();
        for (JobSkill requirement : required) {
            if (requirement.requiredLevel() == null) {
                continue;
            }
            Optional<StudentSkill> match = owned.stream().filter(s -> s.skillId().equals(requirement.skillId())).findFirst();
            if (match.isEmpty() || match.get().proficiencyLevel() == null
                || match.get().proficiencyLevel().compareTo(requirement.requiredLevel()) < 0) {
                missing.add(requirement.skillId());
            }
        }
        List<UnmetRule> unmet = academicRulesUnmet(jobId, studentId);
        return new EligibilityResult(missing.isEmpty() && unmet.isEmpty(), missing, unmet);
    }

    /** The job's deadline and academic rules the student does not meet; the student's standing is only looked up when the job has a rule that needs it. */
    private List<UnmetRule> academicRulesUnmet(UUID jobId, UUID studentId) {
        Optional<Job> job = jobService.findById(jobId);
        if (job.isEmpty()) {
            return List.of();
        }
        var terms = job.get().terms();
        StudentStandings.Standing standing = terms.hasAcademicRules() ? studentStandings.of(studentId).orElse(null) : null;
        return EligibilityRules.unmet(terms, java.time.Instant.now(), standing).stream()
            .map(u -> new UnmetRule(u.code(), u.message())).toList();
    }

    public sealed interface ApplyResult {
        record Success(UUID applicationId) implements ApplyResult {
        }

        record JobNotFound() implements ApplyResult {
        }

        record JobNotOpen() implements ApplyResult {
        }

        record StudentNotFound() implements ApplyResult {
        }

        record NotEligible(List<UUID> missingOrInsufficientSkillIds, List<UnmetRule> unmetRules) implements ApplyResult {
        }

        record AlreadyApplied() implements ApplyResult {
        }
    }

    @Transactional
    public ApplyResult apply(UUID jobId, UUID studentId) {
        Optional<Job> maybeJob = jobService.findById(jobId);
        if (maybeJob.isEmpty()) {
            return new ApplyResult.JobNotFound();
        }
        Job job = maybeJob.get();
        if (job.status() != com.setu.career.domain.JobStatus.OPEN) {
            return new ApplyResult.JobNotOpen();
        }
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty() || !studentInfo.get().tenantId().equals(job.tenantId())) {
            return new ApplyResult.StudentNotFound();
        }
        assertOwnStudent(job.tenantId(), studentId);

        EligibilityResult eligibility = checkEligibility(jobId, studentId);
        if (!eligibility.eligible()) {
            return new ApplyResult.NotEligible(eligibility.missingOrInsufficientSkillIds(), eligibility.unmetRules());
        }

        Application application = new Application(UUID.randomUUID(), job.tenantId(), jobId, studentId, Instant.now());
        try {
            applicationInsertGuard.insert(application);
        } catch (DataIntegrityViolationException e) {
            return new ApplyResult.AlreadyApplied();
        }
        auditLogWriter.write(job.tenantId(), authorizationContext.currentUserId(), "APPLICATION_SUBMITTED",
            "career.applications", application.id(), Map.of("jobId", jobId, "studentId", studentId));
        return new ApplyResult.Success(application.id());
    }

    public sealed interface TransitionResult {
        record Success() implements TransitionResult {
        }

        record NotFound() implements TransitionResult {
        }

        record InvalidTransition() implements TransitionResult {
        }
    }

    @Transactional
    public TransitionResult shortlist(UUID applicationId) {
        return adminTransition(applicationId, ApplicationStatus.SHORTLISTED, "APPLICATION_SHORTLISTED");
    }

    @Transactional
    public TransitionResult reject(UUID applicationId) {
        return adminTransition(applicationId, ApplicationStatus.REJECTED, "APPLICATION_REJECTED");
    }

    @Transactional
    public TransitionResult select(UUID applicationId) {
        return adminTransition(applicationId, ApplicationStatus.SELECTED, "APPLICATION_SELECTED");
    }

    @Transactional
    public TransitionResult withdraw(UUID applicationId) {
        Optional<Application> maybeApplication = applicationRepository.findById(applicationId);
        if (maybeApplication.isEmpty()) {
            return new TransitionResult.NotFound();
        }
        Application application = maybeApplication.get();
        assertOwnStudent(application.tenantId(), application.studentId());
        return doTransition(application, ApplicationStatus.WITHDRAWN, "APPLICATION_WITHDRAWN");
    }

    private TransitionResult adminTransition(UUID applicationId, ApplicationStatus target, String auditAction) {
        Optional<Application> maybeApplication = applicationRepository.findById(applicationId);
        if (maybeApplication.isEmpty()) {
            return new TransitionResult.NotFound();
        }
        Application application = maybeApplication.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("application", application.id(), application.tenantId()));
        return doTransition(application, target, auditAction);
    }

    private TransitionResult doTransition(Application application, ApplicationStatus target, String auditAction) {
        if (!application.canTransitionTo(target)) {
            return new TransitionResult.InvalidTransition();
        }
        int updated = applicationRepository.transitionIfStatus(application.id(), application.status(), target);
        if (updated == 0) {
            return new TransitionResult.InvalidTransition();
        }
        auditLogWriter.write(application.tenantId(), authorizationContext.currentUserId(), auditAction,
            "career.applications", application.id(), Map.of("target", target.name()));
        return new TransitionResult.Success();
    }

    /** Own (student) or a career-management/read permission holder. */
    @Transactional(readOnly = true)
    public List<Application> listForStudent(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return List.of();
        }
        UUID tenantId = studentInfo.get().tenantId();
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isEmpty() || !ownStudentId.get().equals(studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("application", studentId, tenantId));
        }
        return applicationRepository.findByStudentId(studentId);
    }

    /** Placement-officer/admin only. */
    @Transactional(readOnly = true)
    public List<Application> listForJob(UUID jobId) {
        Optional<Job> job = jobService.findById(jobId);
        if (job.isEmpty()) {
            return List.of();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("application", jobId, job.get().tenantId()));
        return applicationRepository.findByJobId(jobId);
    }

    private void assertOwnStudent(UUID tenantId, UUID studentId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        boolean isOwn = ownStudentId.isPresent() && ownStudentId.get().equals(studentId);
        authorizationContext.assertCan(APPLY_PERMISSION,
            ResourceRef.of("application", studentId, tenantId).ownedBy(isOwn ? authorizationContext.currentUserId() : UUID.randomUUID()));
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #apply} — empty on any non-success outcome (e.g. not eligible). */
    @Transactional
    public java.util.Optional<UUID> applyForSeed(UUID jobId, UUID studentId) {
        return switch (apply(jobId, studentId)) {
            case ApplyResult.Success s -> java.util.Optional.of(s.applicationId());
            default -> java.util.Optional.empty();
        };
    }

}
