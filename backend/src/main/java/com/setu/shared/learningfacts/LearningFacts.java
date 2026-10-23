package com.setu.shared.learningfacts;

import com.setu.shared.internalcall.DependencyUnavailableException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the risk engine and the AI advisor need from learning: how a student is doing on assignments and lessons (counts), the published assignments of a section, the lessons of a
 * section and which of them a student has completed. A port, so that they do not read the {@code learning} schema: in the monolith the learning module answers in-process
 * ({@code LocalLearningFacts}); once Learning has a database of its own the answer comes from the Learning service's internal endpoints ({@link RemoteLearningFacts}, switched on
 * by {@code setu.learning.url}).
 * <p>
 * No authorization of its own, like the methods it replaces ({@code AssignmentSubmissionService.listForStudent}, {@code LearningProgressService.listForStudent},
 * {@code AssignmentService.listPublishedForSection}, {@code CourseModuleService.listActiveForSection}, {@code LessonService.listByModuleId}): the caller has already authorized its
 * outer operation. The remote one carries the service-to-service token and the endpoints are not routed by the gateway.
 */
public interface LearningFacts {

    /** A student's submissions (and how many were late) and lesson progress rows (and how many are completed), across everything. */
    record StudentCounts(long submissions, long lateSubmissions, long progressRecords, long completedLessons) {
        public static final StudentCounts NONE = new StudentCounts(0, 0, 0, 0);
    }

    /** An assignment of a section that is visible to students (not a draft). */
    record PublishedAssignment(UUID id, String title, Instant dueAt) {
    }

    /** A lesson of an active module of a section. */
    record LessonRef(UUID lessonId, String title, String type, UUID moduleId) {
    }

    /**
     * Each student's counts; a student with nothing is in the answer with {@link StudentCounts#NONE}.
     *
     * @throws Unavailable if the owner could not be asked or answered something unusable: never a guess, never zeros standing in for an answer
     */
    Map<UUID, StudentCounts> studentCounts(Collection<UUID> studentIds);

    /** The student-visible assignments of each section; a section with none is in the answer with an empty list. @throws Unavailable as above */
    Map<UUID, List<PublishedAssignment>> publishedAssignments(Collection<UUID> sectionIds);

    /** The lessons of each section's active modules, modules and lessons in their own order; a section with none has an empty list. @throws Unavailable as above */
    Map<UUID, List<LessonRef>> lessonsOfSections(Collection<UUID> sectionIds);

    /** The lessons each student has completed; a student with none has an empty set. @throws Unavailable as above */
    Map<UUID, Set<UUID>> completedLessons(Collection<UUID> studentIds);

    /** The owner of the data could not give an answer. Callers say so (retry the event, tell the person) and never substitute data. */
    class Unavailable extends DependencyUnavailableException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
