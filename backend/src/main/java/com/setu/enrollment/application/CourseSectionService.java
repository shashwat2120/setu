package com.setu.enrollment.application;

import com.setu.catalog.application.AcademicPeriodService;
import com.setu.catalog.application.CourseService;
import com.setu.catalog.application.RoomService;
import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.CourseSectionStatus;
import com.setu.enrollment.domain.ClassTenantLookup;
import com.setu.enrollment.domain.EnrollmentStatus;
import com.setu.enrollment.infrastructure.CourseSectionInsertGuard;
import com.setu.enrollment.infrastructure.CourseSectionRepository;
import com.setu.enrollment.infrastructure.EnrollmentRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.directory.FacultyDirectory;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import com.setu.shared.identity.TenantMembership;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Concrete operational course-section offerings (Day 7 §8). NOT the catalog
 * definition ({@code catalog.Course}) — see {@code CourseSection}'s javadoc.
 * Course/period/room references cross into catalog-owned data; each is
 * validated through the narrow ports those services already expose for
 * exactly this purpose ({@code CourseService.findTenantAndStatus},
 * {@code AcademicPeriodService.findTenantAndWindow},
 * {@code RoomService.findTenantAndCapacity}) — plain {@code @NamedInterface}
 * dependencies, the established one-directional
 * enrollment&nbsp;-&gt;&nbsp;catalog pattern (see those classes' javadocs).
 * The optional {@code class_id} is validated purely in-module via the
 * package-private {@code ClassTenantRefRepository}. Transitions follow
 * docs/state-machines.md #10 exactly (see
 * {@link CourseSection#canTransitionTo}).
 * <p>
 * Implements the shared {@link CourseSectionDirectory} port (Day 7): needed
 * by {@code catalog.application.MeetingPatternService} to validate a
 * meeting pattern's section tenant — see {@code shared.enrollment}'s
 * package-info for the full cycle-avoidance justification. That specific
 * caller depends on {@code com.setu.shared.enrollment.CourseSectionDirectory},
 * never on this concrete class, avoiding a real module cycle.
 * <p>
 * {@code @NamedInterface} added Day 15 §demo-data for a different, non-cyclical
 * caller: {@code com.setu.shared.seed.DemoDataSeeder} needs the real {@link
 * #create} operation itself (not just the read-only directory port) to seed
 * course sections through the same authorized path a real registrar would use.
 */
@org.springframework.modulith.NamedInterface
@Service
public class CourseSectionService implements CourseSectionDirectory {

    private static final String MANAGE_PERMISSION = "MANAGE_SECTIONS";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final CourseSectionRepository courseSectionRepository;
    private final CourseSectionInsertGuard courseSectionInsertGuard;
    private final ClassTenantLookup classTenantLookup;
    private final CourseService courseService;
    private final AcademicPeriodService academicPeriodService;
    private final RoomService roomService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final FacultyAssignmentService facultyAssignmentService;
    private final FacultyDirectory facultyDirectory;
    private final com.setu.directory.application.StudentProfileService studentProfileService;
    // Day 16 §student: the repository, not EnrollmentService — injecting the full service here closes a real
    // Spring bean cycle (CourseSectionService -> EnrollmentService -> catalog.MeetingPatternService ->
    // CourseSectionDirectory, implemented by this same class). Both classes live in this module, so a direct
    // repository read is not a module-boundary violation, only a same-module shortcut around that cycle.
    private final EnrollmentRepository enrollmentRepository;
    private final TenantMembership tenantMembership;
    private final OutboxEventWriter outboxEventWriter;

    public CourseSectionService(CourseSectionRepository courseSectionRepository,
                                 CourseSectionInsertGuard courseSectionInsertGuard,
                                 ClassTenantLookup classTenantLookup, CourseService courseService,
                                 AcademicPeriodService academicPeriodService, RoomService roomService,
                                 AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                                 FacultyAssignmentService facultyAssignmentService, FacultyDirectory facultyDirectory,
                                 com.setu.directory.application.StudentProfileService studentProfileService,
                                 EnrollmentRepository enrollmentRepository, TenantMembership tenantMembership,
                                 OutboxEventWriter outboxEventWriter) {
        this.outboxEventWriter = outboxEventWriter;
        this.courseSectionRepository = courseSectionRepository;
        this.courseSectionInsertGuard = courseSectionInsertGuard;
        this.classTenantLookup = classTenantLookup;
        this.courseService = courseService;
        this.academicPeriodService = academicPeriodService;
        this.roomService = roomService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.facultyAssignmentService = facultyAssignmentService;
        this.facultyDirectory = facultyDirectory;
        this.studentProfileService = studentProfileService;
        this.enrollmentRepository = enrollmentRepository;
        this.tenantMembership = tenantMembership;
    }

    public sealed interface CreateResult {
        record Success(UUID sectionId) implements CreateResult {
        }

        record CourseNotFound() implements CreateResult {
        }

        record PeriodNotFound() implements CreateResult {
        }

        record ClassNotFound() implements CreateResult {
        }

        record RoomNotFound() implements CreateResult {
        }

        record TenantMismatch(String resource) implements CreateResult {
        }

        record RoomCapacityInsufficient() implements CreateResult {
        }

        record InvalidSectionData(String message) implements CreateResult {
        }

        record SectionCodeAlreadyInUse() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID courseId, UUID periodId, UUID classId, String sectionCode, int capacity,
                                UUID roomId, Instant registrationOpenAt, Instant registrationCloseAt) {
        Optional<CourseService.CourseTenantStatus> courseInfo = courseService.findTenantAndStatus(courseId);
        if (courseInfo.isEmpty()) {
            return new CreateResult.CourseNotFound();
        }
        UUID tenantId = courseInfo.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course_section", UUID.randomUUID(), tenantId));

        Optional<AcademicPeriodService.PeriodTenantWindow> periodInfo = academicPeriodService.findTenantAndWindow(periodId);
        if (periodInfo.isEmpty()) {
            return new CreateResult.PeriodNotFound();
        }
        if (!periodInfo.get().tenantId().equals(tenantId)) {
            return new CreateResult.TenantMismatch("period");
        }

        if (classId != null) {
            Optional<UUID> classTenantId = classTenantLookup.findTenantId(classId);
            if (classTenantId.isEmpty()) {
                return new CreateResult.ClassNotFound();
            }
            if (!classTenantId.get().equals(tenantId)) {
                return new CreateResult.TenantMismatch("class");
            }
        }

        if (roomId != null) {
            Optional<RoomService.RoomTenantCapacity> roomInfo = roomService.findTenantAndCapacity(roomId);
            if (roomInfo.isEmpty()) {
                return new CreateResult.RoomNotFound();
            }
            if (!roomInfo.get().tenantId().equals(tenantId)) {
                return new CreateResult.TenantMismatch("room");
            }
            if (roomInfo.get().capacity() < capacity) {
                return new CreateResult.RoomCapacityInsufficient();
            }
        }

        if (!withinRegistrationWindow(registrationOpenAt, registrationCloseAt, periodInfo.get())) {
            return new CreateResult.InvalidSectionData(
                "registration window must fall within the academic period's own registration window");
        }

        UUID actor = authorizationContext.currentUserId();
        Instant now = Instant.now();
        CourseSection section;
        try {
            section = new CourseSection(UUID.randomUUID(), tenantId, courseId, periodId, classId, sectionCode,
                capacity, roomId, registrationOpenAt, registrationCloseAt, actor, now);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidSectionData(e.getMessage());
        }
        try {
            courseSectionInsertGuard.insert(section);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.SectionCodeAlreadyInUse();
        }
        auditLogWriter.write(tenantId, actor, "COURSE_SECTION_CREATED", "enrollment.course_sections", section.id(),
            Map.of("courseId", courseId, "periodId", periodId, "sectionCode", sectionCode));
        return new CreateResult.Success(section.id());
    }

    /** Day 7 §8: "registration window must respect academic-calendar policy" — the smallest defensible reading, disclosed: when the period defines its own registration bounds, a section's window must fall within them. */
    private boolean withinRegistrationWindow(Instant sectionOpenAt, Instant sectionCloseAt,
                                              AcademicPeriodService.PeriodTenantWindow periodWindow) {
        if (periodWindow.registrationOpenAt() != null && sectionOpenAt != null
            && sectionOpenAt.isBefore(periodWindow.registrationOpenAt())) {
            return false;
        }
        if (periodWindow.registrationCloseAt() != null && sectionCloseAt != null
            && sectionCloseAt.isAfter(periodWindow.registrationCloseAt())) {
            return false;
        }
        return true;
    }

    /**
     * Day 16 §faculty: a section's own assigned faculty can always view it — real gap this session's frontend
     * work found (FACULTY holds {@code VIEW_ASSIGNED_SECTIONS} per V67's seed grants, but this method only ever
     * checked the tenant-wide {@code VIEW_TENANT_DATA} an ordinary faculty member never holds). Same
     * assigned-faculty-bypass idiom already used by {@code AttendanceSessionService}/{@code AssignmentService}:
     * check assignment first, fall back to the real admin permission only if the caller isn't assigned here.
     */
    @Transactional(readOnly = true)
    /**
     * Day 16 §student: adds a third bypass branch alongside the existing assigned-faculty one — a student
     * actively ENROLLED in this exact section may read its basic schedule/capacity/status (e.g. for "My
     * Courses") without holding the tenant-admin-only {@code VIEW_TENANT_DATA} permission. Deliberately
     * narrower than {@code CourseService#get}'s "any active tenant role" relaxation: an UNassigned,
     * UNenrolled tenant member (e.g. a different faculty member) must still be denied here — section-level
     * detail (capacity/roster implications) is more sensitive than course-catalog-level detail, and
     * {@code unassignedFacultyCannotViewAnotherFacultysSection} already encodes that as required behavior.
     */
    public Optional<CourseSection> get(UUID sectionId) {
        return get(sectionId, null);
    }

    /**
     * Adds a fourth bypass for a guardian viewing on behalf of one student ({@code guardianForStudentId}, passed by
     * the guardian dashboard): allowed only when that exact student is actively ENROLLED in this section AND the
     * caller holds a real, active {@code VIEW_SCHEDULE} delegation for that student. Without it the guardian
     * dashboard's schedule rows could not resolve a section's course (guardians never hold {@code VIEW_TENANT_DATA}).
     */
    @Transactional(readOnly = true)
    public Optional<CourseSection> get(UUID sectionId, UUID guardianForStudentId) {
        Optional<CourseSection> section = courseSectionRepository.findById(sectionId);
        section.ifPresent(s -> {
            if (guardianForStudentId != null && isDelegatedGuardianOfEnrolledStudent(s, guardianForStudentId)) {
                return;
            }
            UUID currentUserId = authorizationContext.currentUserId();
            UUID ownFacultyId = facultyDirectory.findIdByTenantAndOwnerUserId(s.tenantId(), currentUserId).orElse(null);
            boolean isAssignedFaculty = ownFacultyId != null && facultyAssignmentService.isActivelyAssigned(s.id(), ownFacultyId);
            UUID ownStudentId = studentProfileService.findIdByTenantAndOwnerUserId(s.tenantId(), currentUserId).orElse(null);
            boolean isEnrolledStudent = ownStudentId != null && enrollmentRepository.findBySectionIdAndStudentId(s.id(), ownStudentId)
                .filter(e -> e.status() == EnrollmentStatus.ENROLLED).isPresent();
            if (!isAssignedFaculty && !isEnrolledStudent) {
                authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("course_section", s.id(), s.tenantId()));
            }
        });
        return section;
    }

    private boolean isDelegatedGuardianOfEnrolledStudent(CourseSection section, UUID studentId) {
        boolean enrolled = enrollmentRepository.findBySectionIdAndStudentId(section.id(), studentId)
            .filter(e -> e.status() == EnrollmentStatus.ENROLLED).isPresent();
        if (!enrolled) {
            return false;
        }
        var owner = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (owner.isEmpty() || !owner.get().tenantId().equals(section.tenantId())) {
            return false;
        }
        ResourceRef delegated = ResourceRef.of("course_section", section.id(), section.tenantId())
            .ownedBy(owner.get().ownerUserId()).delegatableForStudent(studentId);
        // a denied delegation (no grant, revoked, expired, wrong student) falls through to the normal checks, which deny
        return authorizationContext.can("VIEW_SCHEDULE", delegated).isAllowed();
    }

    @Transactional(readOnly = true)
    public Page<CourseSection> listForTenant(UUID tenantId, Pageable pageable) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("course_section", UUID.randomUUID(), tenantId));
        return courseSectionRepository.findByTenantId(tenantId, pageable);
    }

    /** Registration discovery for active non-guardian members of this tenant; enrollment still enforces the full invariant chain. */
    @Transactional(readOnly = true)
    public Page<CourseSection> listOpenForRegistration(UUID tenantId, Pageable pageable) {
        UUID userId = authorizationContext.currentUserId();
        if (!tenantMembership.hasActiveNonGuardianRoleInTenant(userId, tenantId)) {
            authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("course_section", UUID.randomUUID(), tenantId));
        }
        return courseSectionRepository.findByTenantIdAndStatus(tenantId, CourseSectionStatus.OPEN, pageable);
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidSectionData(String message) implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID sectionId, String sectionCode, int capacity, Instant registrationOpenAt,
                                Instant registrationCloseAt) {
        Optional<CourseSection> maybeSection = courseSectionRepository.findById(sectionId);
        if (maybeSection.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        CourseSection section = maybeSection.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course_section", section.id(), section.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        try {
            section.update(sectionCode, capacity, registrationOpenAt, registrationCloseAt, actor, Instant.now());
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidSectionData(e.getMessage());
        }
        courseSectionRepository.save(section);
        auditLogWriter.write(section.tenantId(), actor, "COURSE_SECTION_UPDATED", "enrollment.course_sections",
            section.id(), Map.of("sectionCode", sectionCode, "capacity", capacity));
        return new UpdateResult.Success();
    }

    public sealed interface ChangeRoomResult {
        record Success() implements ChangeRoomResult {
        }

        record NotFound() implements ChangeRoomResult {
        }

        record RoomNotFound() implements ChangeRoomResult {
        }

        record TenantMismatch() implements ChangeRoomResult {
        }

        record RoomCapacityInsufficient() implements ChangeRoomResult {
        }
    }

    @Transactional
    public ChangeRoomResult changeRoom(UUID sectionId, UUID roomId) {
        Optional<CourseSection> maybeSection = courseSectionRepository.findById(sectionId);
        if (maybeSection.isEmpty()) {
            return new ChangeRoomResult.NotFound();
        }
        CourseSection section = maybeSection.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course_section", section.id(), section.tenantId()));

        if (roomId != null) {
            Optional<RoomService.RoomTenantCapacity> roomInfo = roomService.findTenantAndCapacity(roomId);
            if (roomInfo.isEmpty()) {
                return new ChangeRoomResult.RoomNotFound();
            }
            if (!roomInfo.get().tenantId().equals(section.tenantId())) {
                return new ChangeRoomResult.TenantMismatch();
            }
            if (roomInfo.get().capacity() < section.capacity()) {
                return new ChangeRoomResult.RoomCapacityInsufficient();
            }
        }

        UUID actor = authorizationContext.currentUserId();
        section.changeRoom(roomId, actor, Instant.now());
        courseSectionRepository.save(section);
        auditLogWriter.write(section.tenantId(), actor, "COURSE_SECTION_ROOM_CHANGED", "enrollment.course_sections",
            section.id(), Map.of("roomId", String.valueOf(roomId)));
        return new ChangeRoomResult.Success();
    }

    public enum StatusChangeResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public StatusChangeResult changeStatus(UUID sectionId, CourseSectionStatus target) {
        Optional<CourseSection> maybeSection = courseSectionRepository.findById(sectionId);
        if (maybeSection.isEmpty()) {
            return StatusChangeResult.NOT_FOUND;
        }
        CourseSection section = maybeSection.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course_section", section.id(), section.tenantId()));
        if (!section.canTransitionTo(target)) {
            return StatusChangeResult.INVALID_TRANSITION;
        }
        UUID actor = authorizationContext.currentUserId();
        var from = section.status();
        Instant now = Instant.now();
        section.transitionTo(target, actor, now);
        courseSectionRepository.save(section);
        auditLogWriter.write(section.tenantId(), actor, "COURSE_SECTION_STATUS_CHANGED", "enrollment.course_sections",
            section.id(), Map.of("from", from.name(), "to", target.name()));
        emitStatusEvent(section, target, now);
        return StatusChangeResult.SUCCESS;
    }

    /** Opening and cancelling are the transitions other services act on; closing and drafting are internal bookkeeping. */
    private void emitStatusEvent(CourseSection section, CourseSectionStatus target, Instant now) {
        UUID correlationId = UUID.randomUUID();
        if (target == CourseSectionStatus.OPEN) {
            var event = new com.setu.enrollment.domain.CourseSectionOpenedEvent(UUID.randomUUID(), now, section.tenantId(), section.id(),
                correlationId, null, section.id(), section.courseId(), section.periodId());
            outboxEventWriter.write("course_sections", section.id(), event.eventType(), event.eventVersion(), event, section.tenantId(), correlationId, null);
        } else if (target == CourseSectionStatus.CANCELLED) {
            java.util.List<UUID> affected = enrollmentRepository.findBySectionId(section.id(), org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(e -> e.status() == com.setu.enrollment.domain.EnrollmentStatus.ENROLLED || e.status() == com.setu.enrollment.domain.EnrollmentStatus.WAITLISTED)
                .map(com.setu.enrollment.domain.Enrollment::id).toList();
            var event = new com.setu.enrollment.domain.CourseSectionCancelledEvent(UUID.randomUUID(), now, section.tenantId(), section.id(),
                correlationId, null, section.id(), affected);
            outboxEventWriter.write("course_sections", section.id(), event.eventType(), event.eventVersion(), event, section.tenantId(), correlationId, null);
        }
    }

    /** See this class's javadoc for the {@link CourseSectionDirectory} cross-module justification. */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findTenantId(UUID sectionId) {
        return courseSectionRepository.findById(sectionId).map(CourseSection::tenantId);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID courseId, UUID periodId, UUID classId, String sectionCode, int capacity,
                               UUID roomId, java.time.Instant registrationOpenAt, java.time.Instant registrationCloseAt) {
        CreateResult result = create(courseId, periodId, classId, sectionCode, capacity, roomId, registrationOpenAt, registrationCloseAt);
        if (result instanceof CreateResult.Success s) {
            return s.sectionId();
        }
        throw new IllegalStateException("seed section creation failed: " + sectionCode + " -> " + result);
    }

}
