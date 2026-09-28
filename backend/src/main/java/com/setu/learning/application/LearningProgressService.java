package com.setu.learning.application;

import com.setu.learning.domain.CourseModule;
import com.setu.learning.domain.Lesson;
import com.setu.learning.domain.LearningProgress;
import com.setu.learning.domain.LearningProgressState;
import com.setu.learning.infrastructure.LearningProgressRepository;
import com.setu.learning.infrastructure.LearningProgressUpsertGuard;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 10 §4: learning_progress. Per the authorization matrix, this is the
 * ONE learning resource where FACULTY gets only read access to their own
 * assigned sections — only the owning STUDENT ever writes their own
 * self-paced progress; faculty/admin monitor, they
 * never mark it on a student's behalf, and the student id is always
 * resolved from the CALLER's own principal, never accepted as a request
 * parameter — the same discipline Day 9's attendance scan used to make
 * "students can't mark each other's attendance" true by construction rather
 * than by a runtime check that could be bypassed.
 * <p>
 * First-ever real wiring of guardian delegation to an actual resource in
 * this codebase (the engine and {@code VIEW_LEARNING_DATA} permission code
 * have existed since Day 4/6, unused until now) — a guardian holding an
 * active {@code guardian_access_grants} + {@code guardian_access_permissions}
 * row for {@code VIEW_LEARNING_DATA} may read (never write) their granted
 * student's progress. This is deliberately the ONLY learning resource
 * guardians can reach at all — the authorization matrix leaves the
 * guardian column blank for all three learning rows, so LMS content and
 * assignments/submissions stay guardian-inaccessible; disclosed as the
 * narrowest defensible reading of the Day 10 instruction to delegate only
 * where the required permission already exists — matching the read-own-if-
 * granted pattern used for attendance/grades/schedule elsewhere in the matrix.
 * <p>
 * {@code @NamedInterface} added Day 13: {@link #listForStudent} gives
 * {@code risk.application.RiskFeatureService} every progress row a
 * student has, across all their lessons, for the learning-progress
 * feature — deliberately no authorization of its own, same narrow-port
 * precedent as every other Day 13 cross-module read.
 */
@org.springframework.modulith.NamedInterface
@Service
public class LearningProgressService {

    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";
    private static final String GUARDIAN_PERMISSION = "VIEW_LEARNING_DATA";

    private final LearningProgressRepository learningProgressRepository;
    private final LearningProgressUpsertGuard learningProgressUpsertGuard;
    private final LessonService lessonService;
    private final LearningAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public LearningProgressService(LearningProgressRepository learningProgressRepository,
                                    LearningProgressUpsertGuard learningProgressUpsertGuard, LessonService lessonService,
                                    LearningAccessSupport accessSupport, AuthorizationContext authorizationContext,
                                    AuditLogWriter auditLogWriter) {
        this.learningProgressRepository = learningProgressRepository;
        this.learningProgressUpsertGuard = learningProgressUpsertGuard;
        this.lessonService = lessonService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface RecordResult {
        record Success(UUID progressId) implements RecordResult {
        }

        record LessonNotFound() implements RecordResult {
        }

        record NotEnrolled() implements RecordResult {
        }

        record InvalidProgressData(String message) implements RecordResult {
        }

        /** transaction-concurrency-matrix.md-style scenario: the losing writer of two concurrent progress updates on the SAME row gets this — no silent overwrite, no raw Hibernate exception. */
        record VersionConflict() implements RecordResult {
        }
    }

    /** Student-own-write only — see class javadoc. {@code lastPositionJson} is passed through as-is (already-serialized JSON, or null). */
    @Transactional
    public RecordResult record(UUID lessonId, LearningProgressState state, BigDecimal percent, String lastPositionJson) {
        Optional<Lesson> maybeLesson = lessonService.findById(lessonId);
        if (maybeLesson.isEmpty()) {
            return new RecordResult.LessonNotFound();
        }
        CourseModule module = lessonService.moduleFor(maybeLesson.get()).orElseThrow();

        Optional<UUID> studentId = accessSupport.currentEnrolledStudentId(module.tenantId(), module.sectionId());
        if (studentId.isEmpty()) {
            return new RecordResult.NotEnrolled();
        }

        try {
            LearningProgress.validatePercent(state, percent);
        } catch (IllegalArgumentException e) {
            return new RecordResult.InvalidProgressData(e.getMessage());
        }
        Instant now = Instant.now();

        Optional<LearningProgress> existing = learningProgressRepository.findByLessonIdAndStudentId(lessonId, studentId.get());
        if (existing.isEmpty()) {
            LearningProgress progress = new LearningProgress(UUID.randomUUID(), lessonId, studentId.get());
            progress.record(state, percent, lastPositionJson, now);
            try {
                learningProgressUpsertGuard.insert(progress);
            } catch (DataIntegrityViolationException e) {
                // Lost the create race — the winner's row now exists; fall through as an update against it.
                existing = learningProgressRepository.findByLessonIdAndStudentId(lessonId, studentId.get());
                return applyUpdate(existing.orElseThrow(), state, percent, lastPositionJson, now, module.tenantId());
            }
            auditEvent(module.tenantId(), lessonId, state, progress.id());
            return new RecordResult.Success(progress.id());
        }
        return applyUpdate(existing.get(), state, percent, lastPositionJson, now, module.tenantId());
    }

    private RecordResult applyUpdate(LearningProgress current, LearningProgressState state, BigDecimal percent,
                                      String lastPositionJson, Instant now, UUID tenantId) {
        LearningProgress.Timestamps ts = LearningProgress.computeTimestamps(
            current.state(), current.startedAt(), current.completedAt(), state, now);
        boolean updated = learningProgressUpsertGuard.updateIfVersionMatches(
            current.id(), state, percent, lastPositionJson, ts.startedAt(), ts.completedAt(), current.version());
        if (!updated) {
            return new RecordResult.VersionConflict();
        }
        auditEvent(tenantId, current.lessonId(), state, current.id());
        return new RecordResult.Success(current.id());
    }

    /** Direct-by-id echo of a row this same caller's {@link #record} call just wrote (or is otherwise already known to be theirs) — no controller route exposes bare-id lookup independent of {@link #record}'s own return value. */
    @Transactional(readOnly = true)
    public Optional<LearningProgress> getById(UUID progressId) {
        return learningProgressRepository.findById(progressId);
    }

    private void auditEvent(UUID tenantId, UUID lessonId, LearningProgressState state, UUID progressId) {
        String action = state == LearningProgressState.COMPLETED ? "LEARNING_PROGRESS_COMPLETED" : "LEARNING_PROGRESS_UPDATED";
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), action, "learning.learning_progress",
            progressId, Map.of("lessonId", lessonId, "state", state.name()));
    }

    /** Own, assigned-faculty (read), admin (read), or a guardian with an active VIEW_LEARNING_DATA delegation for this exact student. */
    @Transactional(readOnly = true)
    public Optional<LearningProgress> get(UUID lessonId, UUID studentId) {
        Optional<Lesson> maybeLesson = lessonService.findById(lessonId);
        if (maybeLesson.isEmpty()) {
            return Optional.empty();
        }
        CourseModule module = lessonService.moduleFor(maybeLesson.get()).orElseThrow();
        assertReadAccess(module.tenantId(), module.sectionId(), studentId);
        return learningProgressRepository.findByLessonIdAndStudentId(lessonId, studentId);
    }

    @Transactional(readOnly = true)
    public List<LearningProgress> listForLesson(UUID lessonId) {
        Lesson lesson = lessonService.findById(lessonId).orElseThrow(() -> new IllegalArgumentException("lesson not found"));
        CourseModule module = lessonService.moduleFor(lesson).orElseThrow();
        // Faculty/admin roster view only — a bare listing (not scoped to one student) never matches a guardian delegation
        // (delegation is always for ONE specific granted student) or a student's own single row; both use get() instead.
        if (accessSupport.currentAssignedFacultyId(module.tenantId(), module.sectionId()).isPresent()) {
            return learningProgressRepository.findByLessonId(lessonId);
        }
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("learning_progress", UUID.randomUUID(), module.tenantId()));
        return learningProgressRepository.findByLessonId(lessonId);
    }

    /** Day 13 cross-module port — see class javadoc. Every progress row a student has, across all lessons; no authorization of its own. */
    @Transactional(readOnly = true)
    public List<LearningProgress> listForStudent(UUID studentId) {
        return learningProgressRepository.findByStudentId(studentId);
    }

    private void assertReadAccess(UUID tenantId, UUID sectionId, UUID studentId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            return;
        }
        if (accessSupport.currentAssignedFacultyId(tenantId, sectionId).isPresent()) {
            return;
        }
        // Admin path (VIEW_TENANT_DATA) checked non-throwing first; only a guardian delegation (VIEW_LEARNING_DATA,
        // scoped to this exact granted student) can still allow access if that check fails.
        boolean isAdmin = authorizationContext.can(VIEW_PERMISSION, ResourceRef.of("learning_progress", UUID.randomUUID(), tenantId)).isAllowed();
        if (isAdmin) {
            return;
        }
        // VIEW_LEARNING_DATA is broadly role-granted to STUDENT (V55) — the permission check alone would let ANY
        // student in the tenant pass. ownedBy(...) is what actually excludes every student except a guardian with a
        // genuine delegation (whose access bypasses the ownership check entirely — see AuthorizationService.decide).
        UUID ownerUserId = accessSupport.ownerUserIdOf(studentId).orElse(null);
        ResourceRef resource = ResourceRef.of("learning_progress", UUID.randomUUID(), tenantId)
            .ownedBy(ownerUserId).delegatableForStudent(studentId);
        authorizationContext.assertCan(GUARDIAN_PERMISSION, resource);
    }
}
