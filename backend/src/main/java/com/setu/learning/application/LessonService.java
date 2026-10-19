package com.setu.learning.application;

import com.setu.learning.domain.CourseModule;
import com.setu.learning.domain.Lesson;
import com.setu.learning.domain.LessonType;
import com.setu.learning.infrastructure.CourseModuleRepository;
import com.setu.learning.infrastructure.LessonRepository;
import com.setu.learning.infrastructure.LessonWriteGuard;
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

/**
 * Day 10 §2: lessons CRUD. Ownership flows lesson -&gt; module -&gt; section,
 * so every operation first resolves the owning module (in-module read,
 * {@code course_modules} and {@code lessons} are both {@code learning}'s own
 * tables) before applying the same assigned-faculty-or-admin / enrolled-
 * student idiom {@link CourseModuleService} uses. Students never mutate
 * lesson content (Day 10 explicit rule) — no write path here accepts a
 * student caller under any circumstance, enrolled or not.
 * <p>
 * {@code @NamedInterface} added Day 14: {@link #listByModuleId} gives
 * {@code aiexplain.application}'s {@code recommendLearningResources} tool
 * every lesson for a module - no authorization of its own, same
 * narrow-port precedent as Day 13.
 */
@org.springframework.modulith.NamedInterface
@Service
public class LessonService {

    private static final String MANAGE_PERMISSION = "MANAGE_LEARNING_CONTENT";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final LessonRepository lessonRepository;
    private final LessonWriteGuard lessonWriteGuard;
    private final CourseModuleRepository courseModuleRepository;
    private final LearningAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public LessonService(LessonRepository lessonRepository, LessonWriteGuard lessonWriteGuard,
                          CourseModuleRepository courseModuleRepository,
                          LearningAccessSupport accessSupport, AuthorizationContext authorizationContext,
                          AuditLogWriter auditLogWriter) {
        this.lessonRepository = lessonRepository;
        this.lessonWriteGuard = lessonWriteGuard;
        this.courseModuleRepository = courseModuleRepository;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID lessonId) implements CreateResult {
        }

        record ModuleNotFound() implements CreateResult {
        }

        record InvalidLessonData(String message) implements CreateResult {
        }

        record SequenceAlreadyInUse() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID moduleId, String title, LessonType type, String contentRef, int sequence,
                                Integer durationMinutes) {
        Optional<CourseModule> module = courseModuleRepository.findById(moduleId);
        if (module.isEmpty()) {
            return new CreateResult.ModuleNotFound();
        }
        CourseModule m = module.get();
        assertManage(m.sectionId(), m.tenantId());

        Lesson lesson;
        try {
            lesson = new Lesson(UUID.randomUUID(), moduleId, title, type, contentRef, sequence, durationMinutes);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidLessonData(e.getMessage());
        }
        try {
            lessonWriteGuard.saveAndFlush(lesson);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.SequenceAlreadyInUse();
        }
        auditLogWriter.write(m.tenantId(), authorizationContext.currentUserId(), "LESSON_CREATED",
            "learning.lessons", lesson.id(), Map.of("moduleId", moduleId, "sequence", sequence));
        return new CreateResult.Success(lesson.id());
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidLessonData(String message) implements UpdateResult {
        }

        record SequenceAlreadyInUse() implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID lessonId, String title, LessonType type, String contentRef, int sequence,
                                Integer durationMinutes) {
        Optional<Lesson> maybeLesson = lessonRepository.findById(lessonId);
        if (maybeLesson.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        Lesson lesson = maybeLesson.get();
        CourseModule module = courseModuleRepository.findById(lesson.moduleId()).orElseThrow();
        assertManage(module.sectionId(), module.tenantId());
        try {
            lesson.update(title, type, contentRef, sequence, durationMinutes);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidLessonData(e.getMessage());
        }
        try {
            lessonWriteGuard.saveAndFlush(lesson);
        } catch (DataIntegrityViolationException e) {
            return new UpdateResult.SequenceAlreadyInUse();
        }
        auditLogWriter.write(module.tenantId(), authorizationContext.currentUserId(), "LESSON_UPDATED",
            "learning.lessons", lesson.id(), Map.of("title", title, "sequence", sequence));
        return new UpdateResult.Success();
    }

    public enum DeleteResult {SUCCESS, NOT_FOUND}

    @Transactional
    public DeleteResult delete(UUID lessonId) {
        Optional<Lesson> maybeLesson = lessonRepository.findById(lessonId);
        if (maybeLesson.isEmpty()) {
            return DeleteResult.NOT_FOUND;
        }
        Lesson lesson = maybeLesson.get();
        CourseModule module = courseModuleRepository.findById(lesson.moduleId()).orElseThrow();
        assertManage(module.sectionId(), module.tenantId());
        lessonRepository.delete(lesson);
        auditLogWriter.write(module.tenantId(), authorizationContext.currentUserId(), "LESSON_DELETED",
            "learning.lessons", lessonId, Map.of());
        return DeleteResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public Optional<Lesson> get(UUID lessonId) {
        Optional<Lesson> lesson = lessonRepository.findById(lessonId);
        if (lesson.isEmpty()) {
            return Optional.empty();
        }
        CourseModule module = courseModuleRepository.findById(lesson.get().moduleId()).orElseThrow();
        assertRead(module.sectionId(), module.tenantId());
        return lesson;
    }

    @Transactional(readOnly = true)
    public List<Lesson> listForModule(UUID moduleId) {
        CourseModule module = courseModuleRepository.findById(moduleId)
            .orElseThrow(() -> new IllegalArgumentException("course module not found"));
        assertRead(module.sectionId(), module.tenantId());
        return lessonRepository.findByModuleIdOrderBySequenceAsc(moduleId);
    }

    /** Day 14 cross-module port - see class javadoc. Every lesson for a module, no authorization of its own. */
    @Transactional(readOnly = true)
    public List<Lesson> listByModuleId(UUID moduleId) {
        return lessonRepository.findByModuleIdOrderBySequenceAsc(moduleId);
    }

    /** Exposed for {@link LessonResourceService} to resolve a lesson's owning module without a second lookup round-trip in the caller. */
    Optional<CourseModule> moduleFor(Lesson lesson) {
        return courseModuleRepository.findById(lesson.moduleId());
    }

    Optional<Lesson> findById(UUID lessonId) {
        return lessonRepository.findById(lessonId);
    }

    void assertManage(UUID sectionId, UUID tenantId) {
        if (accessSupport.currentAssignedFacultyId(tenantId, sectionId).isPresent()) {
            return;
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("lesson", UUID.randomUUID(), tenantId));
    }

    void assertRead(UUID sectionId, UUID tenantId) {
        if (accessSupport.currentAssignedFacultyId(tenantId, sectionId).isPresent()) {
            return;
        }
        if (accessSupport.currentViewingStudentId(tenantId, sectionId).isPresent()) {
            return;
        }
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("lesson", UUID.randomUUID(), tenantId));
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID moduleId, String title, com.setu.learning.domain.LessonType type, String contentRef,
                               int sequence, Integer durationMinutes) {
        return switch (create(moduleId, title, type, contentRef, sequence, durationMinutes)) {
            case CreateResult.Success s -> s.lessonId();
            default -> throw new IllegalStateException("seed lesson creation failed: " + title);
        };
    }

}
