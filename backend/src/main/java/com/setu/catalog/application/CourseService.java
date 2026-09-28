package com.setu.catalog.application;

import com.setu.catalog.domain.Course;
import com.setu.catalog.domain.CourseStatus;
import com.setu.catalog.infrastructure.CourseInsertGuard;
import com.setu.catalog.infrastructure.CourseRepository;
import com.setu.identity.application.UserDirectoryService;
import com.setu.institution.application.DepartmentService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Course catalog CRUD and lifecycle (Day 7 §6). A {@link Course} is the
 * catalog DEFINITION — never a section, class, or enrollment (see that
 * class's javadoc). Department tenant ownership is validated through
 * {@code institution.DepartmentService.findTenantId}, the same established
 * port {@code catalog.ProgramService} already uses — never trusting a
 * client-supplied department id at face value. Transitions follow
 * docs/state-machines.md #9 exactly (see {@link Course#canTransitionTo}).
 * <p>
 * {@code @NamedInterface} on this class (Day 7 §3 ceremony):
 * <ol>
 *   <li>Caller: {@code enrollment.application.CourseSectionService}, which
 *       must validate a section's course actually exists and belongs to the
 *       same tenant before accepting it. Day 7 does not require a course to
 *       be in any particular lifecycle status before a section may be
 *       created against it (no such rule is documented), so status is
 *       exposed for the caller's own future use but is not itself
 *       enforced here.</li>
 *   <li>Contract consumed: only {@link #findTenantAndStatus}, a bare
 *       projection — not the whole class, not the {@link Course} entity.</li>
 *   <li>No authorization check inside the lookup itself — the caller has
 *       already authorized its own operation against the section's tenant;
 *       see {@code institution.DepartmentService.findTenantId}'s javadoc for
 *       why this shape is the established pattern.</li>
 *   <li>Verified: {@code ModularityTests} passes with this dependency.</li>
 *   <li>Why this dependency exists: a course section's course reference
 *       crosses from enrollment into catalog-owned data; there is no way to
 *       validate it without some call into catalog, and importing
 *       {@code catalog.infrastructure.CourseRepository} directly would
 *       violate the module boundary.</li>
 * </ol>
 */
@org.springframework.modulith.NamedInterface
@Service
public class CourseService {

    private static final String MANAGE_PERMISSION = "MANAGE_COURSES";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final CourseRepository courseRepository;
    private final CourseInsertGuard courseInsertGuard;
    private final DepartmentService departmentService;
    private final UserDirectoryService userDirectoryService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public CourseService(CourseRepository courseRepository, CourseInsertGuard courseInsertGuard,
                          DepartmentService departmentService, UserDirectoryService userDirectoryService,
                          AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.courseRepository = courseRepository;
        this.courseInsertGuard = courseInsertGuard;
        this.departmentService = departmentService;
        this.userDirectoryService = userDirectoryService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID courseId) implements CreateResult {
        }

        record DepartmentNotFound() implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }

        record InvalidCourseData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID departmentId, String code, String title, BigDecimal credits, String courseType,
                                String level, String description) {
        Optional<UUID> tenantId = departmentService.findTenantId(departmentId);
        if (tenantId.isEmpty()) {
            return new CreateResult.DepartmentNotFound();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course", UUID.randomUUID(), tenantId.get()));

        UUID actor = authorizationContext.currentUserId();
        Course course;
        try {
            course = new Course(UUID.randomUUID(), tenantId.get(), departmentId, code, title, credits, courseType,
                level, description, actor, Instant.now());
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidCourseData(e.getMessage());
        }
        try {
            courseInsertGuard.insert(course);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(tenantId.get(), actor, "COURSE_CREATED", "catalog.courses", course.id(),
            Map.of("code", code, "title", title, "departmentId", departmentId));
        return new CreateResult.Success(course.id());
    }

    @Transactional(readOnly = true)
    /**
     * Day 16 §faculty: real gap found live — a faculty member assigned to teach a section could view the
     * section itself (Day 16's own {@code CourseSectionService} fix) but not the course catalog entry it
     * belongs to, since this previously required tenant-admin-only {@code VIEW_TENANT_DATA}. Course catalog
     * data (code/title/credits/description) isn't sensitive the way a section's roster or a student's record
     * is, so any caller holding ANY active role in the course's own tenant may read it — matching how a real
     * course catalog is ordinarily browsable tenant-wide — with the stricter admin permission kept as the
     * fallback for a caller with no role in this tenant at all.
     */
    public Optional<Course> get(UUID courseId) {
        Optional<Course> course = courseRepository.findById(courseId);
        course.ifPresent(c -> {
            if (!userDirectoryService.hasActiveRoleInTenant(authorizationContext.currentUserId(), c.tenantId())) {
                authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("course", c.id(), c.tenantId()));
            }
        });
        return course;
    }

    @Transactional(readOnly = true)
    public Page<Course> listForTenant(UUID tenantId, Pageable pageable) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("course", UUID.randomUUID(), tenantId));
        return courseRepository.findByTenantId(tenantId, pageable);
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidData(String message) implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID courseId, String title, BigDecimal credits, String courseType, String level,
                                String description) {
        Optional<Course> maybeCourse = courseRepository.findById(courseId);
        if (maybeCourse.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        Course course = maybeCourse.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course", course.id(), course.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        try {
            course.update(title, credits, courseType, level, description, actor, Instant.now());
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidData(e.getMessage());
        }
        courseRepository.save(course);
        auditLogWriter.write(course.tenantId(), actor, "COURSE_UPDATED", "catalog.courses", course.id(),
            Map.of("title", title));
        return new UpdateResult.Success();
    }

    public enum StatusChangeResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public StatusChangeResult changeStatus(UUID courseId, CourseStatus target) {
        Optional<Course> maybeCourse = courseRepository.findById(courseId);
        if (maybeCourse.isEmpty()) {
            return StatusChangeResult.NOT_FOUND;
        }
        Course course = maybeCourse.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course", course.id(), course.tenantId()));
        if (!course.canTransitionTo(target)) {
            return StatusChangeResult.INVALID_TRANSITION;
        }
        UUID actor = authorizationContext.currentUserId();
        var from = course.status();
        course.transitionTo(target, actor, Instant.now());
        courseRepository.save(course);
        auditLogWriter.write(course.tenantId(), actor, "COURSE_STATUS_CHANGED", "catalog.courses", course.id(),
            Map.of("from", from.name(), "to", target.name()));
        return StatusChangeResult.SUCCESS;
    }

    /** See this class's javadoc for the full Day 7 §3 cross-module justification. */
    public record CourseTenantStatus(UUID tenantId, CourseStatus status) {
    }

    @Transactional(readOnly = true)
    public Optional<CourseTenantStatus> findTenantAndStatus(UUID courseId) {
        return courseRepository.findById(courseId).map(c -> new CourseTenantStatus(c.tenantId(), c.status()));
    }

    /**
     * Day 8 §9 cross-module port: {@code enrollment.EnrollmentService} needs
     * a course's credits (for the credit-limit check) alongside its tenant,
     * without exposing the {@link Course} entity. A separate method/record
     * from {@link #findTenantAndStatus} rather than widening that one, so
     * the existing Day 7 caller ({@code enrollment.CourseSectionService})
     * and its tests are untouched.
     */
    public record CourseTenantCredits(UUID tenantId, java.math.BigDecimal credits) {
    }

    @Transactional(readOnly = true)
    public Optional<CourseTenantCredits> findTenantAndCredits(UUID courseId) {
        return courseRepository.findById(courseId).map(c -> new CourseTenantCredits(c.tenantId(), c.credits()));
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID departmentId, String code, String title, java.math.BigDecimal credits,
                               String courseType, String level, String description) {
        return switch (create(departmentId, code, title, credits, courseType, level, description)) {
            case CreateResult.Success s -> s.courseId();
            default -> throw new IllegalStateException("seed course creation failed: " + code);
        };
    }

}
