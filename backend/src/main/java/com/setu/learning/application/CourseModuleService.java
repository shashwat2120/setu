package com.setu.learning.application;

import com.setu.learning.domain.CourseModule;
import com.setu.learning.domain.CourseModuleStatus;
import com.setu.learning.infrastructure.CourseModuleRepository;
import com.setu.learning.infrastructure.CourseModuleWriteGuard;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 10 §1: course_modules CRUD. Assigned-faculty-or-admin for writes (the
 * Day 9 idiom): a faculty member actively assigned to the owning section
 * bypasses the permission check entirely; anyone else falls back to {@code
 * MANAGE_LEARNING_CONTENT} (admin-exclusive, see V64's javadoc). Reads are
 * available to the same assigned faculty, any student with an active
 * ENROLLED enrollment in the section, or an admin holding {@code
 * VIEW_TENANT_DATA}.
 * <p>
 * {@code @NamedInterface} added Day 14: {@link #listActiveForSection}
 * gives {@code aiexplain.application}'s {@code recommendLearningResources}
 * tool every active module for a section - no authorization of its own,
 * same narrow-port precedent as Day 13.
 */
@org.springframework.modulith.NamedInterface
@Service
public class CourseModuleService {

    private static final String MANAGE_PERMISSION = "MANAGE_LEARNING_CONTENT";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final CourseModuleRepository courseModuleRepository;
    private final CourseModuleWriteGuard courseModuleWriteGuard;
    private final CourseSectionDirectory courseSectionDirectory;
    private final LearningAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public CourseModuleService(CourseModuleRepository courseModuleRepository, CourseModuleWriteGuard courseModuleWriteGuard,
                                CourseSectionDirectory courseSectionDirectory,
                                LearningAccessSupport accessSupport, AuthorizationContext authorizationContext,
                                AuditLogWriter auditLogWriter) {
        this.courseModuleRepository = courseModuleRepository;
        this.courseModuleWriteGuard = courseModuleWriteGuard;
        this.courseSectionDirectory = courseSectionDirectory;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID moduleId) implements CreateResult {
        }

        record SectionNotFound() implements CreateResult {
        }

        record InvalidModuleData(String message) implements CreateResult {
        }

        record SequenceAlreadyInUse() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID sectionId, String title, int sequence) {
        Optional<UUID> tenantId = courseSectionDirectory.findTenantId(sectionId);
        if (tenantId.isEmpty()) {
            return new CreateResult.SectionNotFound();
        }
        assertManage(sectionId, tenantId.get());

        CourseModule module;
        try {
            module = new CourseModule(UUID.randomUUID(), tenantId.get(), sectionId, title, sequence);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidModuleData(e.getMessage());
        }
        try {
            courseModuleWriteGuard.saveAndFlush(module);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.SequenceAlreadyInUse();
        }
        auditLogWriter.write(tenantId.get(), authorizationContext.currentUserId(), "COURSE_MODULE_CREATED",
            "learning.course_modules", module.id(), Map.of("sectionId", sectionId, "sequence", sequence));
        return new CreateResult.Success(module.id());
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidModuleData(String message) implements UpdateResult {
        }

        record SequenceAlreadyInUse() implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID moduleId, String title, int sequence) {
        Optional<CourseModule> maybeModule = courseModuleRepository.findById(moduleId);
        if (maybeModule.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        CourseModule module = maybeModule.get();
        assertManage(module.sectionId(), module.tenantId());
        try {
            module.update(title, sequence);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidModuleData(e.getMessage());
        }
        try {
            courseModuleWriteGuard.saveAndFlush(module);
        } catch (DataIntegrityViolationException e) {
            return new UpdateResult.SequenceAlreadyInUse();
        }
        auditLogWriter.write(module.tenantId(), authorizationContext.currentUserId(), "COURSE_MODULE_UPDATED",
            "learning.course_modules", module.id(), Map.of("title", title, "sequence", sequence));
        return new UpdateResult.Success();
    }

    public enum ArchiveResult {SUCCESS, NOT_FOUND}

    @Transactional
    public ArchiveResult setArchived(UUID moduleId, boolean archived) {
        Optional<CourseModule> maybeModule = courseModuleRepository.findById(moduleId);
        if (maybeModule.isEmpty()) {
            return ArchiveResult.NOT_FOUND;
        }
        CourseModule module = maybeModule.get();
        assertManage(module.sectionId(), module.tenantId());
        if (archived) {
            module.archive();
        } else {
            module.reactivate();
        }
        courseModuleRepository.save(module);
        auditLogWriter.write(module.tenantId(), authorizationContext.currentUserId(), "COURSE_MODULE_UPDATED",
            "learning.course_modules", module.id(), Map.of("status", module.status().name()));
        return ArchiveResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public Optional<CourseModule> get(UUID moduleId) {
        Optional<CourseModule> module = courseModuleRepository.findById(moduleId);
        if (module.isEmpty()) {
            return Optional.empty();
        }
        CourseModule m = module.get();
        boolean isFacultyOrAdmin = assertRead(m.sectionId(), m.tenantId());
        if (m.status() == CourseModuleStatus.ARCHIVED && !isFacultyOrAdmin) {
            return Optional.empty();
        }
        return module;
    }

    @Transactional(readOnly = true)
    public List<CourseModule> listForSection(UUID sectionId, UUID tenantId) {
        boolean isFacultyOrAdmin = assertRead(sectionId, tenantId);
        List<CourseModule> all = courseModuleRepository.findBySectionIdOrderBySequenceAsc(sectionId);
        if (isFacultyOrAdmin) {
            return all;
        }
        return all.stream().filter(m -> m.status() == CourseModuleStatus.ACTIVE).toList();
    }

    /** Day 14 cross-module port - see class javadoc. Every ACTIVE module for a section, no authorization of its own. */
    @Transactional(readOnly = true)
    public List<CourseModule> listActiveForSection(UUID sectionId) {
        return courseModuleRepository.findBySectionIdOrderBySequenceAsc(sectionId).stream()
            .filter(m -> m.status() == CourseModuleStatus.ACTIVE)
            .toList();
    }

    private void assertManage(UUID sectionId, UUID tenantId) {
        if (accessSupport.currentAssignedFacultyId(tenantId, sectionId).isPresent()) {
            return;
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course_module", UUID.randomUUID(), tenantId));
    }

    /** Throws if neither assigned faculty, enrolled student, nor a permitted admin. Returns true when the caller is assigned faculty or admin (i.e. archived content should stay visible to them). */
    private boolean assertRead(UUID sectionId, UUID tenantId) {
        if (accessSupport.currentAssignedFacultyId(tenantId, sectionId).isPresent()) {
            return true;
        }
        if (accessSupport.currentEnrolledStudentId(tenantId, sectionId).isPresent()) {
            return false;
        }
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("course_module", UUID.randomUUID(), tenantId));
        return true;
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID sectionId, String title, int sequence) {
        return switch (create(sectionId, title, sequence)) {
            case CreateResult.Success s -> s.moduleId();
            default -> throw new IllegalStateException("seed course module creation failed: " + title);
        };
    }

}
