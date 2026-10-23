package com.setu.assessment.infrastructure.web;

import com.setu.shared.assessmentfacts.AssessmentFacts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The authoritative current grades of a set of enrollments, and the student-visible assessments of a set of sections, for the services that need them to compute something else
 * (the risk engine, the AI advisor, the academic record's transcripts and GPA) and may not read this service's schema. Answers from this service's own data and nothing else.
 * <p>
 * Internal only: {@code /internal/**} requires the service-to-service token ({@code InternalCallAuthFilter}) and is not routed by the gateway. No per-person authorization
 * here by design: the caller has authorized its own outer operation, exactly as with the in-process methods this replaces. One entry per id asked about, so a caller can tell
 * "none" from "not answered".
 */
@RestController
@ConditionalOnExpression("'${setu.assessment.url:}' == ''")
@RequestMapping("/internal/assessment")
public class InternalAssessmentFactsController {

    /** More than a student's enrollments or a student's sections and more than one request carries; a bigger ask is a bug in the caller. */
    static final int MAX_IDS = 100;

    public record Grade(UUID componentId, BigDecimal rawMarks, String gradeCode, BigDecimal gradePoint, String assessmentTitle, String componentCode, BigDecimal weight) {
    }

    public record GradesOf(UUID enrollmentId, List<Grade> grades) {
    }

    public record GradesAnswer(List<GradesOf> results) {
    }

    public record Visible(UUID id, String title, Instant startsAt) {
    }

    public record VisibleOf(UUID sectionId, List<Visible> assessments) {
    }

    public record VisibleAnswer(List<VisibleOf> results) {
    }

    private final AssessmentFacts facts;

    public InternalAssessmentFactsController(AssessmentFacts facts) {
        this.facts = facts;
    }

    @GetMapping("/grades")
    public ResponseEntity<GradesAnswer> grades(@RequestParam List<UUID> enrollmentIds) {
        var ids = new LinkedHashSet<>(enrollmentIds);
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            return ResponseEntity.badRequest().build();
        }
        var found = facts.currentGrades(ids);
        return ResponseEntity.ok(new GradesAnswer(ids.stream()
            .map(id -> new GradesOf(id, found.getOrDefault(id, List.of()).stream()
                .map(g -> new Grade(g.componentId(), g.rawMarks(), g.gradeCode(), g.gradePoint(), g.assessmentTitle(), g.componentCode(), g.weight())).toList()))
            .toList()));
    }

    @GetMapping("/visible")
    public ResponseEntity<VisibleAnswer> visible(@RequestParam List<UUID> sectionIds) {
        var ids = new LinkedHashSet<>(sectionIds);
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            return ResponseEntity.badRequest().build();
        }
        var found = facts.visibleAssessments(ids);
        return ResponseEntity.ok(new VisibleAnswer(ids.stream()
            .map(id -> new VisibleOf(id, found.getOrDefault(id, List.of()).stream().map(a -> new Visible(a.id(), a.title(), a.startsAt())).toList()))
            .toList()));
    }
}
