package com.setu.career.application;

import com.setu.career.domain.Application;
import com.setu.career.domain.ApplicationDecidedEvent;
import com.setu.career.domain.ApplicationStatus;
import com.setu.career.domain.Job;
import com.setu.career.domain.JobSkill;
import com.setu.career.domain.ProficiencyLevel;
import com.setu.career.domain.StudentSkill;
import com.setu.career.infrastructure.ApplicationInsertGuard;
import com.setu.career.infrastructure.ApplicationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.setu.directory.application.StudentProfileService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.career.StudentStandings;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
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
    private final OutboxEventWriter outboxEventWriter;
    private final JobAvailability jobAvailability;
    private final PlacementRecordService placementRecordService;
    private final ResumeService resumeService;

    public ApplicationService(ApplicationRepository applicationRepository, ApplicationInsertGuard applicationInsertGuard,
                               JobService jobService, JobSkillService jobSkillService, StudentSkillService studentSkillService,
                               StudentProfileService studentProfileService, CareerAccessSupport accessSupport,
                               AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter, StudentStandings studentStandings,
                               OutboxEventWriter outboxEventWriter, JobAvailability jobAvailability, PlacementRecordService placementRecordService,
                               ResumeService resumeService) {
        this.resumeService = resumeService;
        this.placementRecordService = placementRecordService;
        this.jobAvailability = jobAvailability;
        this.studentStandings = studentStandings;
        this.outboxEventWriter = outboxEventWriter;
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
        boolean hasResume = !terms.requiresResume() || resumeService.hasCompleteResume(studentId);
        return EligibilityRules.unmet(terms, java.time.Instant.now(), standing, hasResume).stream()
            .map(u -> new UnmetRule(u.code(), u.message())).toList();
    }

    public sealed interface ApplyResult {
        record Success(UUID applicationId) implements ApplyResult {
        }

        record JobNotFound() implements ApplyResult {
        }

        record JobNotOpen() implements ApplyResult {
        }

        /** Every opening has been filled. */
        record JobFull() implements ApplyResult {
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
        if (JobAvailability.FULL.equals(jobAvailability.forJob(job, Instant.now()).closedReason())) {
            return new ApplyResult.JobFull();
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
        // the application is made with the resume as it is now, and keeps it: the officer reviews what was submitted, not later edits
        resumeService.snapshotJson(studentId).ifPresent(s -> application.attachResume(s.json(), s.resumeVersion()));
        try {
            applicationInsertGuard.insert(application);
        } catch (DataIntegrityViolationException e) {
            return new ApplyResult.AlreadyApplied();
        }
        auditLogWriter.write(job.tenantId(), authorizationContext.currentUserId(), "APPLICATION_SUBMITTED",
            "career.applications", application.id(), Map.of("jobId", jobId, "studentId", studentId));
        return new ApplyResult.Success(application.id());
    }

    public sealed interface ResumeResult {
        record Found(ResumeService.Snapshot snapshot) implements ResumeResult {
        }

        /** The application exists but was made without a resume. */
        record None() implements ResumeResult {
        }

        record NotFound() implements ResumeResult {
        }
    }

    public sealed interface ResumeFileResult {
        record Found(ResumeService.Download file) implements ResumeFileResult {
        }

        /** The application was made without a resume file (or the file is no longer in storage). */
        record None() implements ResumeFileResult {
        }

        record NotFound() implements ResumeFileResult {
        }
    }

    /**
     * The resume an application was made with: readable by the student whose application it is and by a placement officer of the college (an officer's reading is audited).
     * Nobody else, and an officer reaches a student's resume only through an application, never by browsing.
     */
    @Transactional
    public ResumeResult resumeOf(UUID applicationId) {
        Optional<Application> maybe = applicationRepository.findById(applicationId);
        if (maybe.isEmpty()) {
            return new ResumeResult.NotFound();
        }
        Application application = maybe.get();
        authorizeResumeReading(application, "RESUME_VIEWED");
        if (application.resumeSnapshot() == null) {
            return new ResumeResult.None();
        }
        return new ResumeResult.Found(resumeService.readSnapshot(application.resumeSnapshot()));
    }

    /** The PDF the application was made with (the exact file, even if the student has since replaced theirs), under the same rules as {@link #resumeOf}. */
    @Transactional
    public ResumeFileResult resumeFileOf(UUID applicationId) {
        Optional<Application> maybe = applicationRepository.findById(applicationId);
        if (maybe.isEmpty()) {
            return new ResumeFileResult.NotFound();
        }
        Application application = maybe.get();
        authorizeResumeReading(application, "RESUME_FILE_DOWNLOADED");
        if (application.resumeSnapshot() == null) {
            return new ResumeFileResult.None();
        }
        return resumeService.load(application.tenantId(), resumeService.readSnapshot(application.resumeSnapshot()).file())
            .<ResumeFileResult>map(ResumeFileResult.Found::new).orElse(new ResumeFileResult.None());
    }

    /** The student whose application it is, or a placement officer of the college (audited); no one else, and no other student whatever their role. */
    private void authorizeResumeReading(Application application, String auditAction) {
        Optional<UUID> own = accessSupport.currentStudentId(application.tenantId());
        boolean isOwn = own.isPresent() && own.get().equals(application.studentId());
        if (own.isPresent() && !isOwn) {
            throw new org.springframework.security.access.AccessDeniedException("permission_denied");
        }
        if (!isOwn) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("application", application.id(), application.tenantId()));
            auditLogWriter.write(application.tenantId(), authorizationContext.currentUserId(), auditAction, "career.applications", application.id(),
                Map.of("studentId", application.studentId(), "jobId", application.jobId()));
        }
    }

    public sealed interface TransitionResult {
        record Success() implements TransitionResult {
        }

        record NotFound() implements TransitionResult {
        }

        record InvalidTransition() implements TransitionResult {
        }

        /** A rejection must say why: the student is shown the reason. */
        record ReasonRequired() implements TransitionResult {
        }

        record ReasonTooLong() implements TransitionResult {
        }

        /** The offer's package or joining date makes no sense; the message says which. */
        record InvalidOffer(String message) implements TransitionResult {
        }

        /** Selecting would go past the job's openings. */
        record OpeningsFilled(int openings) implements TransitionResult {
        }
    }

    /** The longest reason an officer can give a student. */
    public static final int MAX_REASON_LENGTH = 500;

    @Transactional
    public TransitionResult shortlist(UUID applicationId) {
        return shortlist(applicationId, null);
    }

    @Transactional
    public TransitionResult shortlist(UUID applicationId, String reason) {
        return decide(applicationId, ApplicationStatus.SHORTLISTED, reason, "APPLICATION_SHORTLISTED");
    }

    /** A rejection needs a reason, which the student is shown. */
    @Transactional
    public TransitionResult reject(UUID applicationId, String reason) {
        return decide(applicationId, ApplicationStatus.REJECTED, reason, "APPLICATION_REJECTED");
    }

    @Transactional
    public TransitionResult select(UUID applicationId) {
        return select(applicationId, null, null);
    }

    @Transactional
    public TransitionResult select(UUID applicationId, String reason) {
        return select(applicationId, reason, null);
    }

    /** What the officer offers the student they select: the package in lakhs per annum (the job's own when none is given) and the date they join (optional). */
    public record Offer(BigDecimal packageLpa, LocalDate joiningDate) {
        static final Offer NONE = new Offer(null, null);
    }

    /** Selecting a student also records their placement, in the same transaction, from the job and this offer. */
    @Transactional
    public TransitionResult select(UUID applicationId, String reason, Offer offer) {
        Offer o = offer == null ? Offer.NONE : offer;
        if (o.packageLpa() != null && (o.packageLpa().signum() < 0 || o.packageLpa().compareTo(BigDecimal.valueOf(1000)) > 0)) {
            return new TransitionResult.InvalidOffer("the package must be between 0 and 1000 lakhs per annum");
        }
        if (o.joiningDate() != null && o.joiningDate().isBefore(LocalDate.now())) {
            return new TransitionResult.InvalidOffer("the joining date cannot be in the past");
        }
        return decide(applicationId, ApplicationStatus.SELECTED, reason, "APPLICATION_SELECTED", o);
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

    /**
     * An officer's decision: authorised first (so someone with no right to the application learns nothing from the reason's validity), then the reason is checked, then the
     * status moves only from the expected one, and the student is told through the outbox in the same transaction (so a decision is never announced and then rolled back, nor made unannounced).
     */
    private TransitionResult decide(UUID applicationId, ApplicationStatus target, String rawReason, String auditAction) {
        return decide(applicationId, target, rawReason, auditAction, Offer.NONE);
    }

    private TransitionResult decide(UUID applicationId, ApplicationStatus target, String rawReason, String auditAction, Offer offer) {
        Optional<Application> maybeApplication = applicationRepository.findById(applicationId);
        if (maybeApplication.isEmpty()) {
            return new TransitionResult.NotFound();
        }
        Application application = maybeApplication.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("application", application.id(), application.tenantId()));
        String reason = rawReason == null || rawReason.isBlank() ? null : rawReason.strip();
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            return new TransitionResult.ReasonTooLong();
        }
        if (target == ApplicationStatus.REJECTED && reason == null) {
            return new TransitionResult.ReasonRequired();
        }
        if (!application.canTransitionTo(target)) {
            return new TransitionResult.InvalidTransition();
        }
        Instant now = Instant.now();
        if (target == ApplicationStatus.SELECTED) {
            // take the job's row lock first, so two officers selecting for the last opening take turns and the second is told the openings are filled
            Job locked = jobService.lockForUpdate(application.jobId()).orElseThrow();
            Integer openings = locked.terms().openings();
            if (openings != null && applicationRepository.countByJobIdAndStatus(locked.id(), ApplicationStatus.SELECTED) >= openings) {
                return new TransitionResult.OpeningsFilled(openings);
            }
        }
        if (applicationRepository.decideIfStatus(application.id(), application.status(), target, reason, now) == 0) {
            return new TransitionResult.InvalidTransition();
        }
        Job job = jobService.findById(application.jobId()).orElseThrow();
        if (target == ApplicationStatus.SELECTED) {
            // the placement that follows from the selection, made in the same transaction: the package is the offer's, else the job's, in rupees a year
            BigDecimal lpa = offer.packageLpa() != null ? offer.packageLpa() : job.terms().packageLpa();
            BigDecimal ctc = lpa == null ? null : lpa.multiply(BigDecimal.valueOf(100000)).setScale(2, java.math.RoundingMode.HALF_UP);
            var placed = placementRecordService.recordFromSelection(application.tenantId(), application.studentId(), job.id(), job.employer(), job.title(), ctc, offer.joiningDate());
            if (placed instanceof PlacementRecordService.RecordResult.InvalidData invalid) {
                throw new IllegalStateException("the placement for a selection could not be recorded: " + invalid.message());
            }
        }
        UUID correlationId = UUID.randomUUID();
        var event = new ApplicationDecidedEvent(UUID.randomUUID(), now, application.tenantId(), application.id(), correlationId, null,
            application.id(), application.studentId(), job.id(), job.title(), job.employer(), target.name(), reason);
        outboxEventWriter.write("applications", application.studentId(), event.eventType(), event.eventVersion(), event, application.tenantId(), correlationId, null);
        Map<String, Object> details = new java.util.HashMap<>();
        details.put("target", target.name());
        if (reason != null) {
            details.put("reason", reason);
        }
        auditLogWriter.write(application.tenantId(), authorizationContext.currentUserId(), auditAction, "career.applications", application.id(), details);
        return new TransitionResult.Success();
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
