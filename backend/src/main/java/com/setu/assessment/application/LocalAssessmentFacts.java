package com.setu.assessment.application;

import com.setu.shared.assessmentfacts.AssessmentFacts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grades and student-visible assessments, answered from this module's own data. Used wherever the assessment module itself runs (the monolith, and the Assessment service,
 * which answers its internal endpoints with it). Always present; {@code AssessmentFactsRouter} sends a question to it unless this process is set up to ask the Assessment
 * service ({@code setu.assessment.url}, {@code RemoteAssessmentFacts}), and even then while a first-start seeding is running ({@code LocalReads}).
 */
@Component
public class LocalAssessmentFacts implements AssessmentFacts {

    private final GradeService gradeService;
    private final AssessmentComponentService componentService;
    private final AssessmentService assessmentService;

    public LocalAssessmentFacts(GradeService gradeService, AssessmentComponentService componentService, AssessmentService assessmentService) {
        this.gradeService = gradeService;
        this.componentService = componentService;
        this.assessmentService = assessmentService;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<GradeFact>> currentGrades(Collection<UUID> enrollmentIds) {
        Map<UUID, List<com.setu.assessment.domain.Grade>> grades = new LinkedHashMap<>();
        for (UUID enrollmentId : new LinkedHashSet<>(enrollmentIds)) {
            grades.put(enrollmentId, gradeService.findCurrentForEnrollment(enrollmentId));
        }
        var info = componentService.infoUnchecked(grades.values().stream().flatMap(List::stream).map(com.setu.assessment.domain.Grade::componentId).distinct().toList());
        Map<UUID, List<GradeFact>> facts = new LinkedHashMap<>();
        grades.forEach((enrollmentId, list) -> {
            List<GradeFact> out = new ArrayList<>();
            for (var g : list) {
                var i = info.get(g.componentId());
                out.add(new GradeFact(enrollmentId, g.componentId(), g.rawMarks(), g.gradeCode(), g.gradePoint(),
                    i == null ? null : i.assessmentTitle(), i == null ? null : i.componentCode(), i == null ? null : i.weight()));
            }
            facts.put(enrollmentId, List.copyOf(out));
        });
        return facts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<VisibleAssessment>> visibleAssessments(Collection<UUID> sectionIds) {
        Map<UUID, List<VisibleAssessment>> facts = new LinkedHashMap<>();
        for (UUID sectionId : new LinkedHashSet<>(sectionIds)) {
            facts.put(sectionId, assessmentService.listVisibleForSection(sectionId).stream()
                .map(a -> new VisibleAssessment(a.id(), a.title(), a.startsAt())).toList());
        }
        return facts;
    }
}
