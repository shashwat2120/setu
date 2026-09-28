package com.setu.learning.application;

import com.setu.learning.domain.CourseModule;
import com.setu.learning.domain.Lesson;
import com.setu.learning.domain.LessonResource;
import com.setu.learning.domain.LessonResourceType;
import com.setu.learning.infrastructure.LessonResourceRepository;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.authorization.AuthorizationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 10 §3: lesson_resources. Ownership follows the owning lesson (and
 * through it, module/section/tenant) — reuses {@link LessonService}'s
 * package-private module resolution and authorization helpers rather than
 * re-implementing the lesson-&gt;module-&gt;section walk a third time. Binary
 * media never lives here — {@code mediaRef} is validated as non-blank by
 * the entity constructor, its actual object-storage existence is out of
 * this module's scope (no media/object-storage service exists in Sprint 5).
 */
@Service
public class LessonResourceService {

    private final LessonResourceRepository lessonResourceRepository;
    private final LessonService lessonService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public LessonResourceService(LessonResourceRepository lessonResourceRepository, LessonService lessonService,
                                  AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.lessonResourceRepository = lessonResourceRepository;
        this.lessonService = lessonService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface AddResult {
        record Success(UUID resourceId) implements AddResult {
        }

        record LessonNotFound() implements AddResult {
        }

        record InvalidResourceData(String message) implements AddResult {
        }
    }

    @Transactional
    public AddResult add(UUID lessonId, String mediaRef, LessonResourceType type) {
        Optional<Lesson> lesson = lessonService.findById(lessonId);
        if (lesson.isEmpty()) {
            return new AddResult.LessonNotFound();
        }
        CourseModule module = lessonService.moduleFor(lesson.get()).orElseThrow();
        lessonService.assertManage(module.sectionId(), module.tenantId());

        LessonResource resource;
        try {
            resource = new LessonResource(UUID.randomUUID(), lessonId, mediaRef, type);
        } catch (IllegalArgumentException e) {
            return new AddResult.InvalidResourceData(e.getMessage());
        }
        lessonResourceRepository.save(resource);
        auditLogWriter.write(module.tenantId(), authorizationContext.currentUserId(), "LESSON_RESOURCE_ADDED",
            "learning.lesson_resources", resource.id(), Map.of("lessonId", lessonId, "type", type.name()));
        return new AddResult.Success(resource.id());
    }

    public enum RemoveResult {SUCCESS, NOT_FOUND}

    @Transactional
    public RemoveResult remove(UUID resourceId) {
        Optional<LessonResource> maybeResource = lessonResourceRepository.findById(resourceId);
        if (maybeResource.isEmpty()) {
            return RemoveResult.NOT_FOUND;
        }
        LessonResource resource = maybeResource.get();
        Lesson lesson = lessonService.findById(resource.lessonId()).orElseThrow();
        CourseModule module = lessonService.moduleFor(lesson).orElseThrow();
        lessonService.assertManage(module.sectionId(), module.tenantId());

        lessonResourceRepository.delete(resource);
        auditLogWriter.write(module.tenantId(), authorizationContext.currentUserId(), "LESSON_RESOURCE_REMOVED",
            "learning.lesson_resources", resourceId, Map.of("lessonId", resource.lessonId()));
        return RemoveResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public List<LessonResource> listForLesson(UUID lessonId) {
        Lesson lesson = lessonService.findById(lessonId)
            .orElseThrow(() -> new IllegalArgumentException("lesson not found"));
        CourseModule module = lessonService.moduleFor(lesson).orElseThrow();
        lessonService.assertRead(module.sectionId(), module.tenantId());
        return lessonResourceRepository.findByLessonId(lessonId);
    }
}
