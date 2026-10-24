package com.setu.learning.application;

import com.setu.learning.domain.AssignmentSubmissionStatus;
import com.setu.learning.domain.LearningProgress;
import com.setu.learning.domain.LearningProgressState;
import com.setu.shared.learningfacts.LearningFacts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Learning facts, answered from this module's own data. Used wherever the learning module itself runs (the monolith, and the Learning service, which answers its internal
 * endpoints with it). Always present; {@code LearningFactsRouter} sends a question to it unless this process is set up to ask the Learning service
 * ({@code setu.learning.url}, {@code RemoteLearningFacts}), and even then while a first-start seeding is running ({@code LocalReads}).
 */
@Component
public class LocalLearningFacts implements LearningFacts {

    private final AssignmentSubmissionService submissionService;
    private final LearningProgressService progressService;
    private final AssignmentService assignmentService;
    private final CourseModuleService moduleService;
    private final LessonService lessonService;

    public LocalLearningFacts(AssignmentSubmissionService submissionService, LearningProgressService progressService, AssignmentService assignmentService,
                              CourseModuleService moduleService, LessonService lessonService) {
        this.submissionService = submissionService;
        this.progressService = progressService;
        this.assignmentService = assignmentService;
        this.moduleService = moduleService;
        this.lessonService = lessonService;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, StudentCounts> studentCounts(Collection<UUID> studentIds) {
        Map<UUID, StudentCounts> counts = new LinkedHashMap<>();
        for (UUID studentId : new LinkedHashSet<>(studentIds)) {
            var submissions = submissionService.listForStudent(studentId);
            var progress = progressService.listForStudent(studentId);
            counts.put(studentId, new StudentCounts(submissions.size(),
                submissions.stream().filter(s -> s.status() == AssignmentSubmissionStatus.LATE).count(),
                progress.size(), progress.stream().filter(p -> p.state() == LearningProgressState.COMPLETED).count()));
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<PublishedAssignment>> publishedAssignments(Collection<UUID> sectionIds) {
        Map<UUID, List<PublishedAssignment>> facts = new LinkedHashMap<>();
        for (UUID sectionId : new LinkedHashSet<>(sectionIds)) {
            facts.put(sectionId, assignmentService.listPublishedForSection(sectionId).stream()
                .map(a -> new PublishedAssignment(a.id(), a.title(), a.dueAt())).toList());
        }
        return facts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<LessonRef>> lessonsOfSections(Collection<UUID> sectionIds) {
        Map<UUID, List<LessonRef>> facts = new LinkedHashMap<>();
        for (UUID sectionId : new LinkedHashSet<>(sectionIds)) {
            List<LessonRef> lessons = new ArrayList<>();
            for (var module : moduleService.listActiveForSection(sectionId)) {
                for (var lesson : lessonService.listByModuleId(module.id())) {
                    lessons.add(new LessonRef(lesson.id(), lesson.title(), lesson.type().name(), module.id()));
                }
            }
            facts.put(sectionId, List.copyOf(lessons));
        }
        return facts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> completedLessons(Collection<UUID> studentIds) {
        Map<UUID, Set<UUID>> facts = new LinkedHashMap<>();
        for (UUID studentId : new LinkedHashSet<>(studentIds)) {
            facts.put(studentId, progressService.listForStudent(studentId).stream()
                .filter(p -> p.state() == LearningProgressState.COMPLETED).map(LearningProgress::lessonId).collect(Collectors.toUnmodifiableSet()));
        }
        return facts;
    }
}
