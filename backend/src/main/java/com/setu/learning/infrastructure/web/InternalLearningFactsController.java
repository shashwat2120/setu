package com.setu.learning.infrastructure.web;

import com.setu.shared.learningfacts.LearningFacts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Learning facts for the services that need them to compute something else (the risk engine's assignment and lesson features, the AI advisor's deadlines and recommendations) and
 * may not read this service's schema. Answers from this service's own data and nothing else.
 * <p>
 * Internal only: {@code /internal/**} requires the service-to-service token ({@code InternalCallAuthFilter}) and is not routed by the gateway. No per-person authorization here by
 * design: the caller has authorized its own outer operation, exactly as with the in-process methods this replaces. One entry per id asked about, so a caller can tell "none" from
 * "not answered".
 */
@RestController
@ConditionalOnExpression("'${setu.learning.url:}' == ''")
@RequestMapping("/internal/learning")
public class InternalLearningFactsController {

    /** More than a student's sections and more than one request carries; a bigger ask is a bug in the caller. */
    static final int MAX_IDS = 100;

    public record StudentCountsOf(UUID studentId, long submissions, long lateSubmissions, long progressRecords, long completedLessons) {
    }

    public record Assignment(UUID id, String title, Instant dueAt) {
    }

    public record AssignmentsOf(UUID sectionId, List<Assignment> assignments) {
    }

    public record Lesson(UUID lessonId, String title, String type, UUID moduleId) {
    }

    public record LessonsOf(UUID sectionId, List<Lesson> lessons) {
    }

    public record CompletedOf(UUID studentId, Set<UUID> lessonIds) {
    }

    public record Answer<T>(List<T> results) {
    }

    private final LearningFacts facts;

    public InternalLearningFactsController(LearningFacts facts) {
        this.facts = facts;
    }

    @GetMapping("/student-counts")
    public ResponseEntity<Answer<StudentCountsOf>> studentCounts(@RequestParam List<UUID> studentIds) {
        var ids = new LinkedHashSet<>(studentIds);
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            return ResponseEntity.badRequest().build();
        }
        var found = facts.studentCounts(ids);
        return ResponseEntity.ok(new Answer<>(ids.stream().map(id -> {
            var c = found.getOrDefault(id, LearningFacts.StudentCounts.NONE);
            return new StudentCountsOf(id, c.submissions(), c.lateSubmissions(), c.progressRecords(), c.completedLessons());
        }).toList()));
    }

    @GetMapping("/published-assignments")
    public ResponseEntity<Answer<AssignmentsOf>> publishedAssignments(@RequestParam List<UUID> sectionIds) {
        var ids = new LinkedHashSet<>(sectionIds);
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            return ResponseEntity.badRequest().build();
        }
        var found = facts.publishedAssignments(ids);
        return ResponseEntity.ok(new Answer<>(ids.stream().map(id -> new AssignmentsOf(id,
            found.getOrDefault(id, List.of()).stream().map(a -> new Assignment(a.id(), a.title(), a.dueAt())).toList())).toList()));
    }

    @GetMapping("/lessons")
    public ResponseEntity<Answer<LessonsOf>> lessons(@RequestParam List<UUID> sectionIds) {
        var ids = new LinkedHashSet<>(sectionIds);
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            return ResponseEntity.badRequest().build();
        }
        var found = facts.lessonsOfSections(ids);
        return ResponseEntity.ok(new Answer<>(ids.stream().map(id -> new LessonsOf(id,
            found.getOrDefault(id, List.of()).stream().map(l -> new Lesson(l.lessonId(), l.title(), l.type(), l.moduleId())).toList())).toList()));
    }

    @GetMapping("/completed-lessons")
    public ResponseEntity<Answer<CompletedOf>> completedLessons(@RequestParam List<UUID> studentIds) {
        var ids = new LinkedHashSet<>(studentIds);
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            return ResponseEntity.badRequest().build();
        }
        var found = facts.completedLessons(ids);
        return ResponseEntity.ok(new Answer<>(ids.stream().map(id -> new CompletedOf(id, found.getOrDefault(id, Set.of()))).toList()));
    }
}
