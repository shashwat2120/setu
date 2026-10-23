package com.setu.risk.application;

import com.setu.shared.assessmentfacts.AssessmentFacts;
import com.setu.shared.attendancefacts.AttendanceCounts;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.learning.application.AssignmentSubmissionService;
import com.setu.learning.application.LearningProgressService;
import com.setu.learning.domain.AssignmentSubmission;
import com.setu.learning.domain.AssignmentSubmissionStatus;
import com.setu.learning.domain.LearningProgress;
import com.setu.learning.domain.LearningProgressState;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Day 13 §Student Success Engine: computes the deterministic, reproducible
 * feature vector one student's {@link RiskCalculationService} calculation
 * is based on. No specific algorithm/thresholds are mandated by the
 * canonical spec — docs/PRD.md §8 explicitly defers the exact risk-scoring
 * formula and weighting to this engine's own design rather than this
 * document — the five features below are this module's own disclosed, minimal,
 * deterministic design, chosen from exactly the signals the canonical
 * spec names (attendance trend, assessment performance, assignment
 * completion, learning progress, recent failures) and nothing else. A
 * sixth named signal — engagement trend — is deliberately NOT implemented
 * as its own feature — no tracked data source exists anywhere in this
 * schema for login/activity frequency, and this module does not invent
 * one; learning-progress completion is the closest already-real proxy.
 * <p>
 * Every feature is computed only from the student's currently ENROLLED
 * enrollments and their own submissions/
 * progress rows — never from another student's data, never from a
 * different tenant's data (every cross-module call below is scoped by an
 * id already resolved from THIS student's own enrollments).
 * <p>
 * "No data" is treated as neutral (zero risk contribution), never as
 * maximum risk — a student with zero attendance ROWS (not zero
 * attendance) has not been unfairly flagged HIGH risk just because no
 * session has occurred yet; this is a disclosed design choice, not an
 * oversight.
 */
@Component
class RiskFeatureService {

    private final EnrollmentService enrollmentService;
    private final AttendanceCounts attendanceCounts;
    private final AssessmentFacts assessmentFacts;
    private final AssignmentSubmissionService assignmentSubmissionService;
    private final LearningProgressService learningProgressService;

    RiskFeatureService(EnrollmentService enrollmentService, AttendanceCounts attendanceCounts,
                        AssessmentFacts assessmentFacts, AssignmentSubmissionService assignmentSubmissionService,
                        LearningProgressService learningProgressService) {
        this.enrollmentService = enrollmentService;
        this.attendanceCounts = attendanceCounts;
        this.assessmentFacts = assessmentFacts;
        this.assignmentSubmissionService = assignmentSubmissionService;
        this.learningProgressService = learningProgressService;
    }

    /**
     * One computed feature with its raw evidence. {@code evidenceRefs} is
     * a list of {@code {table, id}}-shaped references — see {@link
     * RiskCalculationService} for how these become {@code risk_factors}
     * rows. Evidence granularity, disclosed: each factor references the
     * student's own {@code enrollments} row(s) that were analyzed, rather
     * than every individual attendance_record/grade/submission id — a
     * reviewer can open that enrollment and see the full underlying
     * history; this avoids plumbing record-level evidence through four
     * separate new cross-module ports for a Day 13 scope that does not
     * mandate that granularity.
     */
    record Feature(String code, BigDecimal rawValue, List<EvidenceRef> evidenceRefs) {
    }

    record EvidenceRef(String table, UUID id) {
    }

    record FeatureSnapshot(List<Feature> features, List<UUID> analyzedEnrollmentIds) {
    }

    FeatureSnapshot computeFor(UUID studentId) {
        List<UUID> enrollmentIds = enrollmentService.listActiveEnrollmentRefsForStudent(studentId).stream()
            .map(EnrollmentService.ActiveEnrollmentRef::enrollmentId)
            .toList();
        List<EvidenceRef> enrollmentEvidence = enrollmentIds.stream()
            .map(id -> new EvidenceRef("enrollments", id)).toList();

        List<Feature> features = new ArrayList<>();
        features.add(attendanceFeature(enrollmentIds, enrollmentEvidence));
        List<AssessmentFacts.GradeFact> grades = gradesFor(enrollmentIds);
        features.add(assessmentPerformanceFeature(grades, enrollmentEvidence));
        features.add(recentFailuresFeature(grades, enrollmentEvidence));
        features.add(assignmentCompletionFeature(studentId, enrollmentEvidence));
        features.add(learningProgressFeature(studentId, enrollmentEvidence));
        return new FeatureSnapshot(features, enrollmentIds);
    }

    private Feature attendanceFeature(List<UUID> enrollmentIds, List<EvidenceRef> evidence) {
        long presentOrLate = 0;
        long total = 0;
        // asked of the attendance owner in one call; if it cannot answer, this throws and the recalculation is retried: a score is never computed from guessed attendance
        for (var counts : attendanceCounts.forEnrollments(enrollmentIds).values()) {
            presentOrLate += counts.presentOrLate();
            total += counts.total();
        }
        BigDecimal percentage = total == 0 ? new BigDecimal("100") : new BigDecimal(presentOrLate * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
        return new Feature("ATTENDANCE_TREND", percentage, evidence);
    }

    private List<AssessmentFacts.GradeFact> gradesFor(List<UUID> enrollmentIds) {
        // asked of the assessment owner in one call; if it cannot answer this throws and the recalculation is retried: a score is never computed from guessed grades
        List<AssessmentFacts.GradeFact> grades = new ArrayList<>();
        assessmentFacts.currentGrades(enrollmentIds).values().forEach(grades::addAll);
        return grades;
    }

    private Feature assessmentPerformanceFeature(List<AssessmentFacts.GradeFact> grades, List<EvidenceRef> evidence) {
        List<BigDecimal> points = grades.stream().map(AssessmentFacts.GradeFact::gradePoint).filter(Objects::nonNull).toList();
        BigDecimal average = points.isEmpty() ? new BigDecimal("4.0") // no grades yet — neutral, not risky
            : points.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(points.size()), 3, RoundingMode.HALF_UP);
        return new Feature("ASSESSMENT_PERFORMANCE", average, evidence);
    }

    private Feature recentFailuresFeature(List<AssessmentFacts.GradeFact> grades, List<EvidenceRef> evidence) {
        long failures = grades.stream().filter(g -> "F".equalsIgnoreCase(g.gradeCode())).count();
        return new Feature("RECENT_FAILURES", BigDecimal.valueOf(failures), evidence);
    }

    private Feature assignmentCompletionFeature(UUID studentId, List<EvidenceRef> evidence) {
        List<AssignmentSubmission> submissions = assignmentSubmissionService.listForStudent(studentId);
        if (submissions.isEmpty()) {
            return new Feature("ASSIGNMENT_COMPLETION", BigDecimal.ZERO, evidence); // no submissions yet — neutral
        }
        long late = submissions.stream().filter(s -> s.status() == AssignmentSubmissionStatus.LATE).count();
        BigDecimal latenessRate = BigDecimal.valueOf(late).divide(BigDecimal.valueOf(submissions.size()), 3, RoundingMode.HALF_UP);
        return new Feature("ASSIGNMENT_COMPLETION", latenessRate, evidence);
    }

    private Feature learningProgressFeature(UUID studentId, List<EvidenceRef> evidence) {
        List<LearningProgress> progress = learningProgressService.listForStudent(studentId);
        if (progress.isEmpty()) {
            return new Feature("LEARNING_PROGRESS", new BigDecimal("1.0"), evidence); // no lessons started yet — neutral
        }
        long completed = progress.stream().filter(p -> p.state() == LearningProgressState.COMPLETED).count();
        BigDecimal completionRate = BigDecimal.valueOf(completed).divide(BigDecimal.valueOf(progress.size()), 3, RoundingMode.HALF_UP);
        return new Feature("LEARNING_PROGRESS", completionRate, evidence);
    }
}
