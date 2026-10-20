package com.setu.enrollment.application;

import com.setu.catalog.application.AcademicPeriodService;
import com.setu.catalog.application.CoursePrerequisiteService;
import com.setu.catalog.application.CourseService;
import com.setu.catalog.application.MeetingPatternService;
import com.setu.catalog.domain.AcademicPeriod;
import com.setu.catalog.domain.PrerequisiteRuleType;
import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.Enrollment;
import com.setu.enrollment.domain.EnrollmentAction;
import com.setu.enrollment.domain.EnrollmentCompletedEvent;
import com.setu.enrollment.domain.EnrollmentDroppedEvent;
import com.setu.enrollment.domain.StudentEnrolledEvent;
import com.setu.enrollment.domain.StudentWaitlistedEvent;
import com.setu.enrollment.domain.WaitlistConvertedEvent;
import com.setu.enrollment.domain.EnrollmentOverride;
import com.setu.enrollment.domain.EnrollmentStatus;
import com.setu.enrollment.domain.OverrideStatus;
import com.setu.enrollment.domain.OverrideType;
import com.setu.enrollment.domain.StudentProgramStatus;
import com.setu.enrollment.domain.WaitlistEntry;
import com.setu.enrollment.infrastructure.CourseSectionRepository;
import com.setu.enrollment.infrastructure.EnrollmentActionRepository;
import com.setu.enrollment.infrastructure.EnrollmentOverrideRepository;
import com.setu.enrollment.infrastructure.EnrollmentRepository;
import com.setu.enrollment.infrastructure.StudentProgramRepository;
import com.setu.enrollment.infrastructure.WaitlistEntryRepository;
import com.setu.institution.application.CollegeService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Day 8 core: request/drop a course-section enrollment, implementing
 * the full authoritative invariant chain (docs/erd/02-academic.md,
 * restated exactly):
 * <pre>
 * authenticated student -&gt; active program -&gt; period open -&gt; valid section
 * -&gt; prerequisite satisfied -&gt; no duplicate enrollment -&gt; no schedule
 * conflict -&gt; capacity available OR waitlist -&gt; credit limit valid
 * -&gt; no blocking hold -&gt; optional override -&gt; COMMIT
 * </pre>
 * Every step runs server-side in one transaction; nothing is UI-only.
 * <p>
 * <b>Locking strategy</b> (transaction-concurrency-matrix.md scenario 1):
 * {@link CourseSectionRepository#findByIdForUpdate} takes a
 * {@code SELECT ... FOR UPDATE} row lock on the target section as the FIRST
 * step of the chain (after only the cheap student/tenant existence check),
 * held for the whole transaction. This serializes concurrent last-seat
 * races on that section (the race is over the section's *derived* remaining
 * capacity, not any single enrollment row) and ALSO naturally serializes
 * against a concurrent admin capacity edit
 * ({@code CourseSectionService.update}), since that write blocks on the
 * same row until this transaction commits or rolls back — Day 8 §18 TEST 3.
 * Isolation is default Postgres {@code READ COMMITTED}; the row lock, not a
 * stronger isolation level, is what provides safety here. No retry loop is
 * implemented — the second transaction simply blocks until the lock
 * releases, then re-reads the current state, so there is nothing to retry.
 * <p>
 * Cross-module reads: {@link StudentProfileService} (directory),
 * {@link CourseService}/{@link AcademicPeriodService}/
 * {@link CoursePrerequisiteService}/{@link MeetingPatternService} (catalog),
 * {@link CollegeService} (institution) — all plain {@code @NamedInterface}
 * one-directional ports, the established Day 7 pattern; nothing new crosses
 * back into enrollment from any of them, so no shared-interface indirection
 * is needed here (verified via {@code ModularityTests}).
 * <p>
 * {@code @NamedInterface} on this class (Day 9 §15 ceremony): caller is
 * {@code attendance.application}'s scan flow, which must validate "does
 * this student have an active (ENROLLED) enrollment in this section" before
 * accepting a QR scan — Day 9 §4/§5's explicit requirement. Only
 * {@link #findActiveEnrollmentId} is exposed — a bare id, not the
 * {@link Enrollment} entity, no authorization check of its own (the scan
 * endpoint already authorized the caller as the student themselves before
 * reaching here). No reverse edge from enrollment into attendance exists.
 * Verified via {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class EnrollmentService {

    private static final String MANAGE_PERMISSION = "MANAGE_ENROLLMENTS";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";
    private static final Set<EnrollmentStatus> ACTIVE_LOAD_STATUSES = Set.of(EnrollmentStatus.ENROLLED, EnrollmentStatus.WAITLISTED);

    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentActionRepository actionRepository;
    private final CourseSectionRepository courseSectionRepository;
    private final StudentProgramRepository studentProgramRepository;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final EnrollmentOverrideRepository overrideRepository;
    private final StudentProfileService studentProfileService;
    private final CourseService courseService;
    private final AcademicPeriodService academicPeriodService;
    private final CoursePrerequisiteService coursePrerequisiteService;
    private final MeetingPatternService meetingPatternService;
    private final CollegeService collegeService;
    private final FacultyProfileService facultyProfileService;
    private final FacultyAssignmentService facultyAssignmentService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public EnrollmentService(EnrollmentRepository enrollmentRepository, EnrollmentActionRepository actionRepository,
                              CourseSectionRepository courseSectionRepository, StudentProgramRepository studentProgramRepository,
                              WaitlistEntryRepository waitlistEntryRepository, EnrollmentOverrideRepository overrideRepository,
                              StudentProfileService studentProfileService, CourseService courseService,
                              AcademicPeriodService academicPeriodService, CoursePrerequisiteService coursePrerequisiteService,
                              MeetingPatternService meetingPatternService, CollegeService collegeService,
                              FacultyProfileService facultyProfileService, FacultyAssignmentService facultyAssignmentService,
                              AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                              OutboxEventWriter outboxEventWriter) {
        this.outboxEventWriter = outboxEventWriter;
        this.enrollmentRepository = enrollmentRepository;
        this.actionRepository = actionRepository;
        this.courseSectionRepository = courseSectionRepository;
        this.studentProgramRepository = studentProgramRepository;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.overrideRepository = overrideRepository;
        this.studentProfileService = studentProfileService;
        this.courseService = courseService;
        this.academicPeriodService = academicPeriodService;
        this.coursePrerequisiteService = coursePrerequisiteService;
        this.meetingPatternService = meetingPatternService;
        this.collegeService = collegeService;
        this.facultyProfileService = facultyProfileService;
        this.facultyAssignmentService = facultyAssignmentService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public enum RejectReason {NO_ACTIVE_PROGRAM, PERIOD_NOT_OPEN, SECTION_NOT_OPEN, PREREQUISITE_NOT_SATISFIED, SCHEDULE_CONFLICT, CREDIT_LIMIT_EXCEEDED}

    public sealed interface RequestResult {
        record Enrolled(UUID enrollmentId) implements RequestResult {
        }

        record Waitlisted(UUID enrollmentId, int position) implements RequestResult {
        }

        record Rejected(UUID enrollmentId, RejectReason reason) implements RequestResult {
        }

        record StudentNotFound() implements RequestResult {
        }

        record SectionNotFound() implements RequestResult {
        }

        record TenantMismatch() implements RequestResult {
        }

        record DuplicateActiveEnrollment() implements RequestResult {
        }

        record AlreadyCompleted() implements RequestResult {
        }
    }

    @Transactional
    public RequestResult request(UUID studentId, UUID sectionId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return new RequestResult.StudentNotFound();
        }
        UUID tenantId = studentInfo.get().tenantId();
        UUID currentUserId = authorizationContext.currentUserId();
        boolean isSelf = currentUserId.equals(studentInfo.get().ownerUserId());
        if (!isSelf) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("enrollment", UUID.randomUUID(), tenantId));
        }

        // Lock acquired as the FIRST (and only) read of this row in the transaction, per
        // transaction-concurrency-matrix.md scenario 1 — see class javadoc. A preceding
        // unlocked findById on the same id would leave a stale-version copy of this
        // @Version entity in the persistence context; re-reading it under FOR UPDATE
        // after a concurrent writer has since bumped the version then throws
        // StaleObjectStateException from Hibernate's own version-consistency check,
        // even though PESSIMISTIC_WRITE was requested — a real pitfall this design
        // avoids by never touching the row unlocked first.
        Optional<CourseSection> maybeSection = courseSectionRepository.findByIdForUpdate(sectionId);
        if (maybeSection.isEmpty()) {
            return new RequestResult.SectionNotFound();
        }
        CourseSection section = maybeSection.get();
        if (!section.tenantId().equals(tenantId)) {
            return new RequestResult.TenantMismatch();
        }
        Instant now = Instant.now();

        Optional<Enrollment> existing = enrollmentRepository.findBySectionIdAndStudentId(sectionId, studentId);
        Enrollment enrollment;
        if (existing.isPresent()) {
            Enrollment e = existing.get();
            if (e.status() == EnrollmentStatus.ENROLLED || e.status() == EnrollmentStatus.WAITLISTED
                || e.status() == EnrollmentStatus.REQUESTED) {
                return new RequestResult.DuplicateActiveEnrollment();
            }
            if (e.status() == EnrollmentStatus.COMPLETED) {
                return new RequestResult.AlreadyCompleted();
            }
            e.reopen(now);
            enrollment = e;
        } else {
            enrollment = new Enrollment(UUID.randomUUID(), tenantId, sectionId, studentId, now);
        }
        enrollmentRepository.saveAndFlush(enrollment);
        recordAction(enrollment.id(), "ENROLLMENT_REQUESTED", currentUserId, null, tenantId);

        if (section.status() != com.setu.enrollment.domain.CourseSectionStatus.OPEN) {
            return reject(enrollment, tenantId, currentUserId, RejectReason.SECTION_NOT_OPEN);
        }

        boolean hasActiveProgram = studentProgramRepository.findByStudentId(studentId).stream()
            .anyMatch(sp -> sp.status() == StudentProgramStatus.ACTIVE);
        if (!hasActiveProgram) {
            return reject(enrollment, tenantId, currentUserId, RejectReason.NO_ACTIVE_PROGRAM);
        }

        Optional<AcademicPeriodService.PeriodRegistrationInfo> periodInfo = academicPeriodService.findRegistrationInfo(section.periodId());
        if (periodInfo.isEmpty() || periodInfo.get().status() != AcademicPeriod.PeriodStatus.REGISTRATION_OPEN) {
            return reject(enrollment, tenantId, currentUserId, RejectReason.PERIOD_NOT_OPEN);
        }

        if (!prerequisitesSatisfied(studentId, section.courseId())) {
            if (!consumeOverrideIfUsable(studentId, sectionId, OverrideType.PREREQUISITE, now, currentUserId, enrollment.id(), tenantId)) {
                return reject(enrollment, tenantId, currentUserId, RejectReason.PREREQUISITE_NOT_SATISFIED);
            }
        }

        if (hasScheduleConflict(studentId, section)) {
            return reject(enrollment, tenantId, currentUserId, RejectReason.SCHEDULE_CONFLICT);
        }

        BigDecimal courseCredits = courseService.findTenantAndCredits(section.courseId()).map(CourseService.CourseTenantCredits::credits)
            .orElse(BigDecimal.ZERO);
        Optional<BigDecimal> maxCredits = collegeService.maxCreditsPerPeriod(tenantId);
        if (maxCredits.isPresent()) {
            BigDecimal projectedLoad = currentCreditLoad(studentId, section.periodId(), sectionId).add(courseCredits);
            if (projectedLoad.compareTo(maxCredits.get()) > 0) {
                if (!consumeOverrideIfUsable(studentId, sectionId, OverrideType.CREDIT_LIMIT, now, currentUserId, enrollment.id(), tenantId)) {
                    return reject(enrollment, tenantId, currentUserId, RejectReason.CREDIT_LIMIT_EXCEEDED);
                }
            }
        }

        // No hold subsystem exists anywhere in the canonical schema (disclosed gap, Day 8 §10) — this step is a documented no-op.

        long enrolledCount = enrollmentRepository.countBySectionIdAndStatus(sectionId, EnrollmentStatus.ENROLLED);
        boolean hasCapacity = enrolledCount < section.capacity();
        if (!hasCapacity) {
            boolean capacityOverride = consumeOverrideIfUsable(studentId, sectionId, OverrideType.CAPACITY, now, currentUserId, enrollment.id(), tenantId);
            if (!capacityOverride) {
                return waitlist(enrollment, section, tenantId, currentUserId, now);
            }
        }

        enrollment.markEnrolled(now);
        enrollmentRepository.save(enrollment);
        recordAction(enrollment.id(), "ENROLLMENT_APPROVED", currentUserId, null, tenantId);
        emitEnrolled(enrollment, tenantId, now, false);
        return new RequestResult.Enrolled(enrollment.id());
    }

    /** Every move into ENROLLED, however it happened, tells the services that keep their own view of who is enrolled where. */
    private void emitEnrolled(Enrollment enrollment, UUID tenantId, Instant now, boolean viaWaitlist) {
        UUID correlationId = UUID.randomUUID();
        var event = new StudentEnrolledEvent(UUID.randomUUID(), now, tenantId, enrollment.id(), correlationId, null,
            enrollment.id(), enrollment.sectionId(), enrollment.studentId(), viaWaitlist);
        outboxEventWriter.write("enrollments", enrollment.id(), event.eventType(), event.eventVersion(), event, tenantId, correlationId, null);
    }

    private RequestResult.Rejected reject(Enrollment enrollment, UUID tenantId, UUID actor, RejectReason reason) {
        enrollment.markRejected();
        enrollmentRepository.save(enrollment);
        recordAction(enrollment.id(), "ENROLLMENT_REJECTED", actor, reason.name(), tenantId);
        return new RequestResult.Rejected(enrollment.id(), reason);
    }

    private RequestResult.Waitlisted waitlist(Enrollment enrollment, CourseSection section, UUID tenantId, UUID actor, Instant now) {
        enrollment.markWaitlisted();
        enrollmentRepository.save(enrollment);
        Optional<WaitlistEntry> existingEntry = waitlistEntryRepository.findBySectionIdAndStudentId(section.id(), enrollment.studentId());
        int nextPosition = waitlistEntryRepository.findMaxWaitingPosition(section.id()) + 1;
        WaitlistEntry entry;
        if (existingEntry.isPresent()) {
            entry = existingEntry.get();
            entry.reactivate(nextPosition, now);
        } else {
            entry = new WaitlistEntry(UUID.randomUUID(), tenantId, section.id(), enrollment.studentId(), nextPosition, now);
        }
        waitlistEntryRepository.save(entry);
        recordAction(enrollment.id(), "ENROLLMENT_WAITLISTED", actor, null, tenantId);
        UUID correlationId = UUID.randomUUID();
        var event = new StudentWaitlistedEvent(UUID.randomUUID(), now, tenantId, enrollment.id(), correlationId, null,
            enrollment.id(), section.id(), enrollment.studentId(), nextPosition);
        outboxEventWriter.write("enrollments", enrollment.id(), event.eventType(), event.eventVersion(), event, tenantId, correlationId, null);
        return new RequestResult.Waitlisted(enrollment.id(), nextPosition);
    }

    private boolean prerequisitesSatisfied(UUID studentId, UUID courseId) {
        List<CoursePrerequisiteService.PrerequisiteRequirement> requirements = coursePrerequisiteService.listRequirementsForCourse(courseId);
        for (var requirement : requirements) {
            if (requirement.ruleType() != PrerequisiteRuleType.REQUIRED) {
                continue; // RECOMMENDED/COREQUISITE are non-blocking for Day 8's scope — disclosed in the Day 8 report.
            }
            boolean satisfied = enrollmentRepository.findByStudentIdAndStatusIn(studentId, Set.of(EnrollmentStatus.COMPLETED)).stream()
                .filter(e -> courseSectionRepository.findById(e.sectionId()).map(CourseSection::courseId)
                    .map(cid -> cid.equals(requirement.prerequisiteCourseId())).orElse(false))
                .anyMatch(e -> requirement.minimumGrade() == null
                    // no minimum: any pass will do. A completed row with no recorded grade (from before final grades were recorded) still counts.
                    ? e.finalStatus() == null || FinalGrade.isPass(e.finalStatus())
                    : FinalGrade.atLeast(e.finalStatus(), requirement.minimumGrade()));
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    private boolean hasScheduleConflict(UUID studentId, CourseSection candidate) {
        List<Enrollment> activeElsewhere = enrollmentRepository.findByStudentIdAndStatusIn(studentId, Set.of(EnrollmentStatus.ENROLLED)).stream()
            .filter(e -> !e.sectionId().equals(candidate.id()))
            .toList();
        List<UUID> samePeriodSectionIds = activeElsewhere.stream()
            .map(e -> courseSectionRepository.findById(e.sectionId()).orElse(null))
            .filter(s -> s != null && s.periodId().equals(candidate.periodId()))
            .map(CourseSection::id)
            .toList();
        if (samePeriodSectionIds.isEmpty()) {
            return false;
        }
        List<MeetingPatternService.MeetingPatternSummary> candidatePatterns = meetingPatternService.findActivePatternsForSections(Set.of(candidate.id()));
        List<MeetingPatternService.MeetingPatternSummary> otherPatterns = meetingPatternService.findActivePatternsForSections(samePeriodSectionIds);
        for (var a : candidatePatterns) {
            for (var b : otherPatterns) {
                if (patternsOverlap(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean patternsOverlap(MeetingPatternService.MeetingPatternSummary a, MeetingPatternService.MeetingPatternSummary b) {
        if (a.dayOfWeek() != b.dayOfWeek()) {
            return false;
        }
        boolean timeOverlap = a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime());
        if (!timeOverlap) {
            return false;
        }
        return datesOverlap(a.effectiveFrom(), a.effectiveTo(), b.effectiveFrom(), b.effectiveTo());
    }

    private boolean datesOverlap(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        boolean aEndsBeforeBStarts = aTo != null && bFrom != null && !aTo.isAfter(bFrom);
        boolean bEndsBeforeAStarts = bTo != null && aFrom != null && !bTo.isAfter(aFrom);
        return !(aEndsBeforeBStarts || bEndsBeforeAStarts);
    }

    private BigDecimal currentCreditLoad(UUID studentId, UUID periodId, UUID excludingSectionId) {
        List<Enrollment> active = enrollmentRepository.findByStudentIdAndStatusIn(studentId, ACTIVE_LOAD_STATUSES);
        Map<UUID, BigDecimal> creditsByCourse = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Enrollment e : active) {
            if (e.sectionId().equals(excludingSectionId)) {
                continue;
            }
            CourseSection s = courseSectionRepository.findById(e.sectionId()).orElse(null);
            if (s == null || !s.periodId().equals(periodId)) {
                continue;
            }
            BigDecimal credits = creditsByCourse.computeIfAbsent(s.courseId(),
                cid -> courseService.findTenantAndCredits(cid).map(CourseService.CourseTenantCredits::credits).orElse(BigDecimal.ZERO));
            total = total.add(credits);
        }
        return total;
    }

    private boolean consumeOverrideIfUsable(UUID studentId, UUID sectionId, OverrideType type, Instant now, UUID actor,
                                             UUID enrollmentId, UUID tenantId) {
        Optional<EnrollmentOverride> maybe = overrideRepository.findFirstByStudentIdAndSectionIdAndTypeAndStatusOrderByExpiresAtDesc(
            studentId, sectionId, type, OverrideStatus.ACTIVE);
        if (maybe.isEmpty() || !maybe.get().isUsable(now)) {
            return false;
        }
        EnrollmentOverride override = maybe.get();
        override.markUsed();
        overrideRepository.save(override);
        Map<String, Object> metadata = Map.of("overrideId", override.id(), "overrideType", type.name());
        recordActionWithMetadata(enrollmentId, "ENROLLMENT_OVERRIDE_USED", actor, null, metadata, tenantId);
        return true;
    }

    public sealed interface DropResult {
        record Success(boolean promotedWaitlist) implements DropResult {
        }

        record NotFound() implements DropResult {
        }

        record InvalidTransition() implements DropResult {
        }

        record StudentNotFound() implements DropResult {
        }
    }

    @Transactional
    public DropResult drop(UUID enrollmentId, String reason) {
        Optional<Enrollment> maybeEnrollment = enrollmentRepository.findById(enrollmentId);
        if (maybeEnrollment.isEmpty()) {
            return new DropResult.NotFound();
        }
        Enrollment enrollment = maybeEnrollment.get();
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(enrollment.studentId());
        if (studentInfo.isEmpty()) {
            return new DropResult.StudentNotFound();
        }
        UUID currentUserId = authorizationContext.currentUserId();
        boolean isSelf = currentUserId.equals(studentInfo.get().ownerUserId());
        if (!isSelf) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("enrollment", enrollment.id(), enrollment.tenantId()));
        }
        if (enrollment.status() != EnrollmentStatus.ENROLLED && enrollment.status() != EnrollmentStatus.WAITLISTED) {
            return new DropResult.InvalidTransition();
        }

        CourseSection section = courseSectionRepository.findByIdForUpdate(enrollment.sectionId()).orElseThrow();
        Instant now = Instant.now();
        boolean wasEnrolled = enrollment.status() == EnrollmentStatus.ENROLLED;
        enrollment.markDropped(now);
        enrollmentRepository.save(enrollment);
        recordAction(enrollment.id(), "ENROLLMENT_DROPPED", currentUserId, reason, enrollment.tenantId());
        UUID droppedCorrelation = UUID.randomUUID();
        var droppedEvent = new EnrollmentDroppedEvent(UUID.randomUUID(), now, enrollment.tenantId(), enrollment.id(), droppedCorrelation, null,
            enrollment.id(), enrollment.sectionId(), enrollment.studentId(), now, wasEnrolled);
        outboxEventWriter.write("enrollments", enrollment.id(), droppedEvent.eventType(), droppedEvent.eventVersion(), droppedEvent,
            enrollment.tenantId(), droppedCorrelation, null);

        boolean promoted = false;
        if (wasEnrolled) {
            promoted = promoteNextWaitlisted(section, now, currentUserId);
        }
        return new DropResult.Success(promoted);
    }

    /** transaction-concurrency-matrix.md scenario 2: SKIP LOCKED lets two concurrent drops each promote a DIFFERENT waitlisted student instead of one blocking the other. */
    private boolean promoteNextWaitlisted(CourseSection section, Instant now, UUID actor) {
        Optional<WaitlistEntry> maybeNext = waitlistEntryRepository.findNextWaitingForUpdateSkipLocked(section.id());
        if (maybeNext.isEmpty()) {
            return false;
        }
        WaitlistEntry next = maybeNext.get();
        next.convert();
        waitlistEntryRepository.save(next);

        List<WaitlistEntry> after = waitlistEntryRepository.findBySectionIdAndStatusAndPositionGreaterThan(
            section.id(), com.setu.enrollment.domain.WaitlistStatus.WAITING, next.position());
        for (WaitlistEntry entry : after) {
            entry.decrementPosition();
            waitlistEntryRepository.save(entry);
        }

        Enrollment promoted = enrollmentRepository.findBySectionIdAndStudentId(section.id(), next.studentId()).orElseThrow();
        promoted.markEnrolled(now);
        enrollmentRepository.save(promoted);
        recordAction(promoted.id(), "ENROLLMENT_WAITLIST_PROMOTED", actor, null, promoted.tenantId());
        UUID correlationId = UUID.randomUUID();
        var converted = new WaitlistConvertedEvent(UUID.randomUUID(), now, promoted.tenantId(), section.id(), correlationId, null,
            next.id(), promoted.id(), section.id(), promoted.studentId());
        outboxEventWriter.write("waitlist_entries", section.id(), converted.eventType(), converted.eventVersion(), converted,
            promoted.tenantId(), correlationId, null);
        emitEnrolled(promoted, promoted.tenantId(), now, true);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<Enrollment> get(UUID enrollmentId) {
        Optional<Enrollment> enrollment = enrollmentRepository.findById(enrollmentId);
        enrollment.ifPresent(e -> {
            Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(e.studentId());
            boolean isSelf = studentInfo.isPresent() && authorizationContext.currentUserId().equals(studentInfo.get().ownerUserId());
            if (!isSelf) {
                authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("enrollment", e.id(), e.tenantId()));
            }
        });
        return enrollment;
    }

    /**
     * Day 16 §guardian: real gap found live — a guardian with an active VIEW_SCHEDULE delegation had no way to
     * even discover which sections the student is enrolled in, since this had no delegation bypass at all
     * (unlike GradeService/AttendanceRecordService, which already gate their OWN reads this way). Without this,
     * the entire guardian data-viewing chain was broken at its very first step: grades/attendance/learning
     * progress are all keyed by enrollmentId, and a guardian could never obtain one for a student they have
     * real, active, scoped access to.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Enrollment> listForStudent(UUID studentId, org.springframework.data.domain.Pageable pageable) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        boolean isSelf = authorizationContext.currentUserId().equals(studentInfo.get().ownerUserId());
        if (!isSelf
            && !authorizationContext.can(VIEW_PERMISSION, ResourceRef.of("enrollment", UUID.randomUUID(), studentInfo.get().tenantId())).isAllowed()) {
            ResourceRef delegated = ResourceRef.of("enrollment", UUID.randomUUID(), studentInfo.get().tenantId())
                .ownedBy(studentInfo.get().ownerUserId()).delegatableForStudent(studentId);
            authorizationContext.assertCan("VIEW_SCHEDULE", delegated);
        }
        return enrollmentRepository.findByStudentId(studentId, pageable);
    }

    /**
     * Day 16 §faculty: real gap found live — a faculty member assigned to teach a section could not list even
     * their OWN section's enrollments, since this previously required tenant-admin-only {@code VIEW_TENANT_DATA}
     * with no assigned-faculty bypass (unlike {@code CourseSectionService#get}, {@code AttendanceSessionService},
     * etc., which already have this exact idiom). Same pattern: assigned faculty bypasses the admin permission,
     * everyone else still needs it.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Enrollment> listForSection(UUID sectionId, UUID tenantId, org.springframework.data.domain.Pageable pageable) {
        Optional<UUID> ownFacultyId = facultyProfileService.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId());
        boolean isAssignedFaculty = ownFacultyId.isPresent() && facultyAssignmentService.isActivelyAssigned(sectionId, ownFacultyId.get())
            && authorizationContext.holdsFacultyRole(tenantId);
        if (!isAssignedFaculty) {
            authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("enrollment", UUID.randomUUID(), tenantId));
        }
        return enrollmentRepository.findBySectionId(sectionId, pageable);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentAction> history(UUID enrollmentId) {
        return actionRepository.findByEnrollmentIdOrderByCreatedAtAsc(enrollmentId);
    }

    /** See this class's javadoc for the full Day 9 §15 cross-module justification. */
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveEnrollmentId(UUID sectionId, UUID studentId) {
        return enrollmentRepository.findBySectionIdAndStudentId(sectionId, studentId)
            .filter(e -> e.status() == EnrollmentStatus.ENROLLED)
            .map(Enrollment::id);
    }

    /** The enrollment that can be graded: ENROLLED, or COMPLETED (its final grade was published and late grades or corrections may still follow). */
    @Transactional(readOnly = true)
    public Optional<UUID> findGradableEnrollmentId(UUID sectionId, UUID studentId) {
        return enrollmentRepository.findBySectionIdAndStudentId(sectionId, studentId)
            .filter(e -> e.status() == EnrollmentStatus.ENROLLED || e.status() == EnrollmentStatus.COMPLETED)
            .map(Enrollment::id);
    }

    /** Day 11 §Grades cross-module port: {@code assessment.application.GradeService} needs to resolve an enrollment's owning student/section/tenant from a bare {@code enrollmentId} (grades reference {@code enrollment_id} directly, per the ERD) without importing the {@code Enrollment} entity across the module boundary. */
    public record EnrollmentSummary(UUID tenantId, UUID sectionId, UUID studentId) {
    }

    /** See this class's javadoc for the full Day 9 §15 cross-module justification — same pattern, new caller. */
    @Transactional(readOnly = true)
    public Optional<EnrollmentSummary> findEnrollmentSummary(UUID enrollmentId) {
        return enrollmentRepository.findById(enrollmentId)
            .map(e -> new EnrollmentSummary(e.tenantId(), e.sectionId(), e.studentId()));
    }

    /**
     * Day 9 §14/§15 cross-module port: closing an attendance session must
     * generate a system {@code ABSENT} row (docs/state-machines.md #15) for
     * every enrolled-but-unscanned student — a bare enrollment-id list, no
     * per-call authorization (the caller, {@code attendance.
     * AttendanceSessionService}, has already authorized the faculty's right
     * to close the session before reaching here).
     */
    @Transactional(readOnly = true)
    public List<UUID> listActiveEnrollmentIds(UUID sectionId) {
        return enrollmentRepository.findBySectionId(sectionId, org.springframework.data.domain.Pageable.unpaged())
            .stream().filter(e -> e.status() == EnrollmentStatus.ENROLLED).map(Enrollment::id).toList();
    }

    /** A bare {@code (enrollmentId, sectionId)} pair — enough for a cross-module caller to resolve section-level facts (e.g. faculty assignment) without importing the {@link Enrollment} entity itself. */
    public record ActiveEnrollmentRef(UUID enrollmentId, UUID sectionId) {
    }

    /**
     * Day 13 §Student Success Engine cross-module port: every ENROLLED
     * enrollment for one student, across all their sections — no
     * authorization of its own (same precedent as {@link
     * #listActiveEnrollmentIds} and {@link #findEnrollmentSummary} above).
     * {@code risk.application} needs this from two different call sites:
     * one where the caller has already been authorized (feature
     * computation, after {@code RiskCalculationService} has asserted
     * manage/view access) and one where authorization is still being
     * DECIDED ({@code RiskAccessSupport#currentUserIsAdvisorOf}, which
     * cannot itself require the caller to already hold a broad enrollment-
     * view permission — that's exactly the permission a plain advising
     * faculty member does not have). {@link #listForStudent} was tried
     * first for both and rejected: it enforces its own self-or-broad-
     * permission check, which fails for a faculty member who is only
     * entitled to see THIS student because they advise them, not because
     * they hold tenant-wide enrollment visibility.
     */
    @Transactional(readOnly = true)
    public List<ActiveEnrollmentRef> listActiveEnrollmentRefsForStudent(UUID studentId) {
        return enrollmentRepository.findByStudentId(studentId, org.springframework.data.domain.Pageable.unpaged())
            .stream().filter(e -> e.status() == EnrollmentStatus.ENROLLED)
            .map(e -> new ActiveEnrollmentRef(e.id(), e.sectionId())).toList();
    }

    /**
     * As {@link #listActiveEnrollmentRefsForStudent}, plus the courses the student has finished: every enrollment a student holds grades in (ENROLLED or
     * COMPLETED). For what a student's results are read from, where a finished course's final grade must not disappear when the course ends. No
     * authorization of its own, the same precedent as the method above.
     */
    @Transactional(readOnly = true)
    public List<ActiveEnrollmentRef> listEnrolledOrCompletedRefsForStudent(UUID studentId) {
        return enrollmentRepository.findByStudentId(studentId, org.springframework.data.domain.Pageable.unpaged())
            .stream().filter(e -> e.status() == EnrollmentStatus.ENROLLED || e.status() == EnrollmentStatus.COMPLETED)
            .map(e -> new ActiveEnrollmentRef(e.id(), e.sectionId())).toList();
    }

    /** A section as a person knows it: its course's code and title, and the section's own code ("CS101", "Introduction to Programming", "CS101-A"). */
    public record SectionLabel(String courseCode, String courseTitle, String sectionCode) {
    }

    /**
     * The label of a section, with no authorization of its own (the same precedent as the ports above): for a caller that has already decided the reader may
     * see the student's records (the advisor's facts), so a line can say "CS101" and not a section id. Empty if the section or its course cannot be found.
     */
    @Transactional(readOnly = true)
    public Optional<SectionLabel> labelOfSection(UUID sectionId) {
        return courseSectionRepository.findById(sectionId)
            .flatMap(s -> courseService.summaryUnchecked(s.courseId()).map(c -> new SectionLabel(c.code(), c.title(), s.sectionCode())));
    }

    /** What publishing a final grade did to an enrollment. */
    enum CompletionResult {
        /** ENROLLED became COMPLETED with this final grade. */
        COMPLETED,
        /** The enrollment was already COMPLETED and its final grade changed. */
        CORRECTED,
        /** The enrollment was already COMPLETED with this very grade: nothing to do. */
        UNCHANGED,
        /** The enrollment is not one a final grade applies to (dropped, waitlisted, rejected ...): nothing to do. */
        NOT_ENROLLED
    }

    /**
     * Publishing a final grade completes the enrollment: ENROLLED becomes COMPLETED and the grade becomes its {@code final_status}. A later
     * correction of that grade changes the final status and leaves the enrollment COMPLETED. Driven by the {@code FinalGradePublished} event from
     * the Assessment service, so there is no signed-in user: the person on record is the faculty member who published the grade, and the checks
     * that make a system action safe are made here (the enrollment must exist and belong to the tenant the event names).
     * <p>
     * Writes an {@code enrollment_actions} row (append-only) and an {@code EnrollmentCompleted} event in the same transaction. Package-private on
     * purpose: the only caller is {@link FinalGradeEventProcessor}, which has already made the event idempotent and serialised it per enrollment.
     *
     * @param gradeCode the final grade as published
     * @param publishedBy the faculty member who published it
     * @throws EnrollmentNotKnownException if there is no such enrollment (yet)
     * @throws PoisonEventException if the enrollment belongs to a different tenant, or the grade code is unusable
     */
    @Transactional
    CompletionResult completeWithFinalGrade(UUID enrollmentId, UUID eventTenantId, String gradeCode, UUID publishedBy) {
        Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
            .orElseThrow(() -> new EnrollmentNotKnownException("enrollment " + enrollmentId + " is not known"));
        if (!enrollment.tenantId().equals(eventTenantId)) {
            throw new PoisonEventException("event tenant does not match the tenant of enrollment " + enrollmentId);
        }
        String finalGrade;
        try {
            finalGrade = FinalGrade.normalize(gradeCode);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException(e.getMessage(), e);
        }

        boolean corrected;
        String action;
        switch (enrollment.status()) {
            case ENROLLED -> {
                enrollment.markCompleted(finalGrade);
                corrected = false;
                action = "ENROLLMENT_COMPLETED";
            }
            case COMPLETED -> {
                if (finalGrade.equals(enrollment.finalStatus())) {
                    return CompletionResult.UNCHANGED;
                }
                enrollment.correctFinalStatus(finalGrade);
                corrected = true;
                action = "ENROLLMENT_FINAL_GRADE_CORRECTED";
            }
            default -> {
                return CompletionResult.NOT_ENROLLED;
            }
        }
        enrollmentRepository.save(enrollment);
        recordActionWithMetadata(enrollment.id(), action, publishedBy, null, Map.of("finalGrade", finalGrade), enrollment.tenantId());

        CourseSection section = courseSectionRepository.findById(enrollment.sectionId()).orElseThrow();
        UUID correlationId = UUID.randomUUID();
        var event = new EnrollmentCompletedEvent(UUID.randomUUID(), Instant.now(), enrollment.tenantId(), enrollment.id(), correlationId, null,
            enrollment.id(), enrollment.sectionId(), enrollment.studentId(), section.periodId(), finalGrade, FinalGrade.isPass(finalGrade), corrected);
        outboxEventWriter.write("enrollments", enrollment.id(), event.eventType(), event.eventVersion(), event, enrollment.tenantId(),
            correlationId, null);
        return corrected ? CompletionResult.CORRECTED : CompletionResult.COMPLETED;
    }

    private void recordAction(UUID enrollmentId, String action, UUID actor, String reason, UUID tenantId) {
        recordActionWithMetadata(enrollmentId, action, actor, reason, null, tenantId);
    }

    private void recordActionWithMetadata(UUID enrollmentId, String action, UUID actor, String reason,
                                           Map<String, Object> metadata, UUID tenantId) {
        String metadataJson = null;
        if (metadata != null) {
            metadataJson = metadata.entrySet().stream()
                .map(en -> "\"" + en.getKey() + "\":\"" + en.getValue() + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(body -> "{" + body + "}")
                .orElse(null);
        }
        actionRepository.save(new EnrollmentAction(UUID.randomUUID(), enrollmentId, action, actor, reason, metadataJson, Instant.now()));
        auditLogWriter.write(tenantId, actor, action, "enrollment.enrollments", enrollmentId,
            reason == null ? Map.of() : Map.of("reason", reason));
    }
}
