package com.setu.aifacts.application;

import com.setu.assessment.application.AssessmentComponentService;
import com.setu.assessment.application.GradeService;
import com.setu.assessment.application.AssessmentService;
import com.setu.assessment.domain.Assessment;
import com.setu.assessment.domain.Grade;
import com.setu.shared.attendancefacts.AttendanceCounts;
import com.setu.career.application.ApplicationService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.finance.application.StudentChargeService;
import com.setu.record.application.AcademicRecordService;
import com.setu.institution.application.PolicyDocumentService;
import com.setu.learning.application.AssignmentService;
import com.setu.learning.application.CourseModuleService;
import com.setu.learning.application.LearningProgressService;
import com.setu.learning.application.LessonService;
import com.setu.learning.domain.Assignment;
import com.setu.learning.domain.CourseModule;
import com.setu.learning.domain.Lesson;
import com.setu.learning.domain.LearningProgress;
import com.setu.learning.domain.LearningProgressState;
import com.setu.risk.application.RiskCalculationService;
import com.setu.risk.domain.RiskFactor;
import com.setu.risk.domain.RiskScore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The data behind the AI advisor's tools, and the authorization that guards it. Day 14 §AI / canonical spec §10 / TRD §6: each fact is backed
 * ENTIRELY by existing, already-verified application services, never a repository, never raw SQL, never a datasource of the AI module's own.
 * Every fact resolves its target student via {@link AiAccessSupport#resolveTargetStudent} and (except {@link #searchCollegePolicy}, which is
 * tenant-scoped rather than student-scoped, and {@link #getSkillGap}, which delegates entirely to {@link ApplicationService#previewEligibility}'s
 * own self/admin-only check) authorizes it via {@link AiAccessSupport} BEFORE touching any data: the model supplies only WHICH student a
 * question is about; it never decides whether the caller may see the answer.
 * <p>
 * Since 6B.3 this lives here, in the {@code aifacts} module, not in {@code aiexplain}: the AI service (which has no datasource and calls no
 * other module) asks for these facts through {@link com.setu.shared.aifacts.AiFacts}, and over the network the question arrives on
 * {@code /ai-facts/**} carrying the <i>user's own</i> bearer token, so this class decides with the same principal, tenant and permissions as
 * for any request of that user. Nothing here trusts the AI service to say who is asking.
 * <p>
 * Every returned record is a plain structured DTO; nothing here returns free text the model could mistake for its own reasoning.
 */
@Component
public class AiFactsService {

    private final AiAccessSupport accessSupport;
    private final EnrollmentService enrollmentService;
    private final AttendanceCounts attendanceCounts;
    private final GradeService gradeService;
    private final RiskCalculationService riskCalculationService;
    private final AssignmentService assignmentService;
    private final AssessmentService assessmentService;
    private final ApplicationService applicationService;
    private final CourseModuleService courseModuleService;
    private final LessonService lessonService;
    private final LearningProgressService learningProgressService;
    private final PolicyDocumentService policyDocumentService;
    private final AcademicRecordService academicRecordService;
    private final StudentChargeService studentChargeService;
    private final AssessmentComponentService assessmentComponentService;

    public AiFactsService(AiAccessSupport accessSupport, EnrollmentService enrollmentService,
                          AttendanceCounts attendanceCounts, GradeService gradeService,
                          RiskCalculationService riskCalculationService, AssignmentService assignmentService,
                          AssessmentService assessmentService, ApplicationService applicationService,
                          CourseModuleService courseModuleService, LessonService lessonService,
                          LearningProgressService learningProgressService, PolicyDocumentService policyDocumentService,
                          AcademicRecordService academicRecordService,
                          StudentChargeService studentChargeService, AssessmentComponentService assessmentComponentService) {
        this.assessmentComponentService = assessmentComponentService;
        this.academicRecordService = academicRecordService;
        this.studentChargeService = studentChargeService;
        this.accessSupport = accessSupport;
        this.enrollmentService = enrollmentService;
        this.attendanceCounts = attendanceCounts;
        this.gradeService = gradeService;
        this.riskCalculationService = riskCalculationService;
        this.assignmentService = assignmentService;
        this.assessmentService = assessmentService;
        this.applicationService = applicationService;
        this.courseModuleService = courseModuleService;
        this.lessonService = lessonService;
        this.learningProgressService = learningProgressService;
        this.policyDocumentService = policyDocumentService;
    }

    public record ToolError(String reason) {
    }

    // ---- 1. getStudentAttendance ----------------------------------------------------------

    /**
     * One course's own attendance: the same arithmetic as the overall figure, for the one enrollment. {@code course} and {@code section} are what a person recognises.
     * {@code percentage} is null when no session has been held in this course yet: a course with nothing to attend has no percentage, and must not read as 100%.
     */
    public record CourseAttendance(String course, String section, Double percentage, long presentOrLate, long total) {
    }

    /** {@code percentage}, {@code presentOrLate} and {@code total} are across ALL the student's enrolled sections; {@code courses} breaks the same sessions down by course. */
    public record AttendanceResult(double percentage, long presentOrLate, long total, List<CourseAttendance> courses) {
    }

    public Object getStudentAttendance(
        String studentId) {
        var target = accessSupport.resolveTargetStudent(studentId);
        if (target.isEmpty()) {
            return new ToolError("student not found");
        }
        var t = target.get();
        try {
            accessSupport.assertView(t.tenantId(), t.studentId(), t.ownerUserId(), "VIEW_ATTENDANCE");
        } catch (AccessDeniedException e) {
            return new ToolError("not authorized to view this student's attendance");
        }
        long presentOrLate = 0;
        long total = 0;
        List<CourseAttendance> courses = new ArrayList<>();
        var refs = enrollmentService.listActiveEnrollmentRefsForStudent(t.studentId());
        Map<UUID, AttendanceCounts.Counts> counts;
        try {
            counts = attendanceCounts.forEnrollments(refs.stream().map(ref -> ref.enrollmentId()).toList());
        } catch (AttendanceCounts.Unavailable e) {
            // said plainly: the advisor must not present a number it could not get
            return new ToolError("attendance is temporarily unavailable, so no attendance figure can be given right now");
        }
        for (var ref : refs) {
            var pct = counts.get(ref.enrollmentId());
            presentOrLate += pct.presentOrLate();
            total += pct.total();
            var label = enrollmentService.labelOfSection(ref.sectionId());
            courses.add(new CourseAttendance(
                label.map(l -> l.courseCode() + " " + l.courseTitle()).orElse("a course"),
                label.map(EnrollmentService.SectionLabel::sectionCode).orElse(null),
                pct.total() == 0 ? null : round1((pct.presentOrLate() * 100.0) / pct.total()), pct.presentOrLate(), pct.total()));
        }
        double percentage = total == 0 ? 100.0 : (presentOrLate * 100.0) / total;
        return new AttendanceResult(round1(percentage), presentOrLate, total, List.copyOf(courses));
    }

    // ---- 2. getAcademicProgress -------------------------------------------------------------

    /**
     * One published grade. The title of the assessment and the component's code are what a person recognises ("Midterm Quiz", "MAIN"); the id alone
     * made the advisor say "Component ID 428bfae7...". Both are null if the caller may not read them, never guessed.
     */
    public record ComponentGrade(UUID componentId, String assessmentTitle, String componentCode, String gradeCode, BigDecimal gradePoint) {
    }

    public record RiskSummary(String band, String ruleVersion, Instant calculatedAt, List<String> topFactorCodes) {
    }

    public record AcademicProgressResult(BigDecimal averageGradePoint, List<ComponentGrade> grades, RiskSummary risk) {
    }

    public Object getAcademicProgress(
        String studentId) {
        var target = accessSupport.resolveTargetStudent(studentId);
        if (target.isEmpty()) {
            return new ToolError("student not found");
        }
        var t = target.get();

        // Grades and risk are two INDEPENDENTLY governed permissions (VIEW_ACADEMIC_STATUS vs. VIEW_RISK_SCORE —
        // a guardian may legitimately be granted one without the other, matching authorization-matrix.md's
        // separate rows for each). Each half is authorized on its own; the tool only refuses outright if BOTH
        // are denied, never gating the whole response on just one of the two.
        boolean canViewGrades = true;
        List<ComponentGrade> componentGrades = List.of();
        BigDecimal average = null;
        try {
            accessSupport.assertView(t.tenantId(), t.studentId(), t.ownerUserId(), "VIEW_ACADEMIC_STATUS");
            List<Grade> grades = new ArrayList<>();
            for (var ref : enrollmentService.listEnrolledOrCompletedRefsForStudent(t.studentId())) {
                grades.addAll(gradeService.findCurrentForEnrollment(ref.enrollmentId()));
            }
            componentGrades = grades.stream().map(this::named).toList();
            List<BigDecimal> points = grades.stream().map(Grade::gradePoint).filter(Objects::nonNull).toList();
            average = points.isEmpty() ? null
                : points.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(points.size()), 2, RoundingMode.HALF_UP);
        } catch (AccessDeniedException e) {
            canViewGrades = false;
        }

        RiskSummary risk = null;
        boolean canViewRisk = true;
        try {
            var current = riskCalculationService.getCurrent(t.studentId());
            if (current.isPresent()) {
                RiskScore score = current.get();
                List<String> topFactors = riskCalculationService.factorsFor(score.id()).stream()
                    .sorted(Comparator.comparing(RiskFactor::contribution).reversed())
                    .limit(3).map(RiskFactor::factorCode).toList();
                risk = new RiskSummary(score.band().name(), score.ruleVersion(), score.calculatedAt(), topFactors);
            }
        } catch (AccessDeniedException e) {
            canViewRisk = false;
        }

        if (!canViewGrades && !canViewRisk) {
            return new ToolError("not authorized to view this student's academic progress");
        }
        return new AcademicProgressResult(average, componentGrades, risk);
    }

    // ---- 3. getAssessmentResults ------------------------------------------------------------

    public Object getAssessmentResults(
        String studentId) {
        var target = accessSupport.resolveTargetStudent(studentId);
        if (target.isEmpty()) {
            return new ToolError("student not found");
        }
        var t = target.get();
        try {
            accessSupport.assertView(t.tenantId(), t.studentId(), t.ownerUserId(), "VIEW_GRADES");
        } catch (AccessDeniedException e) {
            return new ToolError("not authorized to view this student's grades");
        }
        List<ComponentGrade> results = new ArrayList<>();
        for (var ref : enrollmentService.listEnrolledOrCompletedRefsForStudent(t.studentId())) {
            for (Grade g : gradeService.findCurrentForEnrollment(ref.enrollmentId())) {
                results.add(named(g));
            }
        }
        return results;
    }

    /** A grade with the name of what it is a grade for, as far as the caller is allowed to read it. */
    private ComponentGrade named(Grade g) {
        String title = null;
        String code = null;
        try {
            var label = assessmentComponentService.labelOf(g.componentId());
            if (label.isPresent()) {
                code = label.get().componentCode();
                title = label.get().assessmentTitle();
            }
        } catch (AccessDeniedException e) {
            // not allowed to read the assessment itself: the grade is shown without its name
        }
        return new ComponentGrade(g.componentId(), title, code, g.gradeCode(), g.gradePoint());
    }

    // ---- 4. getUpcomingDeadlines ------------------------------------------------------------

    public record Deadline(String type, UUID id, String title, Instant dueAt) {
    }

    private static final int MAX_DEADLINES = 10;

    public Object getUpcomingDeadlines(
        String studentId) {
        var target = accessSupport.resolveTargetStudent(studentId);
        if (target.isEmpty()) {
            return new ToolError("student not found");
        }
        var t = target.get();
        try {
            accessSupport.assertView(t.tenantId(), t.studentId(), t.ownerUserId(), "VIEW_SCHEDULE");
        } catch (AccessDeniedException e) {
            return new ToolError("not authorized to view this student's schedule");
        }
        Instant now = Instant.now();
        List<Deadline> deadlines = new ArrayList<>();
        for (var ref : enrollmentService.listActiveEnrollmentRefsForStudent(t.studentId())) {
            for (Assignment a : assignmentService.listPublishedForSection(ref.sectionId())) {
                if (a.dueAt() != null && a.dueAt().isAfter(now)) {
                    deadlines.add(new Deadline("ASSIGNMENT", a.id(), a.title(), a.dueAt()));
                }
            }
            for (Assessment a : assessmentService.listVisibleForSection(ref.sectionId())) {
                if (a.startsAt() != null && a.startsAt().isAfter(now)) {
                    deadlines.add(new Deadline("ASSESSMENT", a.id(), a.title(), a.startsAt()));
                }
            }
        }
        return deadlines.stream().sorted(Comparator.comparing(Deadline::dueAt)).limit(MAX_DEADLINES).toList();
    }

    // ---- 5. getSkillGap -----------------------------------------------------------------------

    public record SkillGapResult(boolean eligible, List<UUID> missingOrInsufficientSkillIds, List<String> unmetRules) {
    }

    public Object getSkillGap(
        String jobId,
        String studentId) {
        UUID job;
        try {
            job = UUID.fromString(jobId.trim());
        } catch (Exception e) {
            return new ToolError("invalid jobId");
        }
        var target = accessSupport.resolveTargetStudent(studentId);
        if (target.isEmpty()) {
            return new ToolError("student not found");
        }
        try {
            var result = applicationService.previewEligibility(job, target.get().studentId());
            if (result.isEmpty()) {
                return new ToolError("job not found");
            }
            return new SkillGapResult(result.get().eligible(), result.get().missingOrInsufficientSkillIds(),
                result.get().unmetRules().stream().map(r -> r.message()).toList());
        } catch (AccessDeniedException e) {
            return new ToolError("not authorized to view this student's skill profile");
        }
    }

    // ---- 6. searchCollegePolicy ---------------------------------------------------------------

    public record PolicySearchResult(UUID documentId, String title, String content) {
    }

    public Object searchCollegePolicy(String query) {
        return policyDocumentService.search(query).stream()
            .map(r -> new PolicySearchResult(r.documentId(), r.title(), r.content()))
            .toList();
    }

    // ---- 7. recommendLearningResources ---------------------------------------------------------

    public record LearningResource(UUID lessonId, String title, String type, UUID moduleId, UUID sectionId) {
    }

    private static final int MAX_RESOURCES = 5;

    public Object recommendLearningResources(
        String studentId) {
        var target = accessSupport.resolveTargetStudent(studentId);
        if (target.isEmpty()) {
            return new ToolError("student not found");
        }
        var t = target.get();
        try {
            accessSupport.assertView(t.tenantId(), t.studentId(), t.ownerUserId(), "VIEW_LEARNING_DATA");
        } catch (AccessDeniedException e) {
            return new ToolError("not authorized to view this student's learning data");
        }
        Set<UUID> completedLessonIds = learningProgressService.listForStudent(t.studentId()).stream()
            .filter(p -> p.state() == LearningProgressState.COMPLETED)
            .map(LearningProgress::lessonId)
            .collect(java.util.stream.Collectors.toSet());

        List<LearningResource> recommendations = new ArrayList<>();
        for (var ref : enrollmentService.listActiveEnrollmentRefsForStudent(t.studentId())) {
            for (CourseModule module : courseModuleService.listActiveForSection(ref.sectionId())) {
                for (Lesson lesson : lessonService.listByModuleId(module.id())) {
                    if (!completedLessonIds.contains(lesson.id())) {
                        recommendations.add(new LearningResource(lesson.id(), lesson.title(), lesson.type().name(),
                            module.id(), ref.sectionId()));
                    }
                }
            }
        }
        return recommendations.stream().limit(MAX_RESOURCES).toList();
    }

    // ---- 8. getTranscript ---------------------------------------------------------------------

    public record TranscriptCourse(String courseCode, String courseTitle, BigDecimal credits, String enrollmentStatus,
                                    BigDecimal courseGradePoint, List<BigDecimal> gradePoints) {
    }

    public record TranscriptResult(BigDecimal gpa, BigDecimal creditsCompleted, BigDecimal creditsRequired,
                                    BigDecimal degreeProgressPercent, boolean hasActiveProgram,
                                    List<TranscriptCourse> courses) {
    }

    public Object getTranscript(
        String studentId) {
        var target = accessSupport.resolveTargetStudent(studentId);
        if (target.isEmpty()) {
            return new ToolError("student not found");
        }
        var t = target.get();
        try {
            // AcademicRecordService authorizes the caller itself (self / admin / guardian grant) on both calls
            var transcript = academicRecordService.transcript(t.studentId(), t.tenantId());
            var progress = academicRecordService.degreeProgress(t.studentId(), t.tenantId());
            List<TranscriptCourse> courses = transcript.lines().stream()
                .map(l -> new TranscriptCourse(l.courseCode(), l.courseTitle(), l.credits(), l.enrollmentStatus(),
                    l.courseGradePoint(), l.grades().stream().map(AcademicRecordService.GradeLine::gradePoint).filter(Objects::nonNull).toList()))
                .toList();
            return new TranscriptResult(transcript.gpa(), progress.creditsCompleted(), progress.creditsRequired(),
                progress.percentage(), progress.hasActiveProgram(), courses);
        } catch (AccessDeniedException e) {
            return new ToolError("not authorized to view this student's transcript");
        }
    }

    // ---- 9. getFeeStatus ----------------------------------------------------------------------

    public record FeeCharge(BigDecimal amount, String currency, java.time.LocalDate dueDate, String status) {
    }

    public record FeeStatusResult(BigDecimal outstanding, String currency, List<FeeCharge> charges) {
    }

    public Object getFeeStatus(
        String studentId) {
        var target = accessSupport.resolveTargetStudent(studentId);
        if (target.isEmpty()) {
            return new ToolError("student not found");
        }
        try {
            // StudentChargeService authorizes the caller itself (self / finance admin / guardian grant)
            var charges = studentChargeService.listForStudent(target.get().studentId());
            List<FeeCharge> lines = charges.stream()
                .map(c -> new FeeCharge(c.amount(), c.currency(), c.dueDate(), c.status().name()))
                .sorted(Comparator.comparing(FeeCharge::dueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
            BigDecimal outstanding = lines.stream()
                .filter(c -> c.status().equals("PENDING") || c.status().equals("OVERDUE"))
                .map(FeeCharge::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String currency = lines.isEmpty() ? null : lines.get(0).currency();
            return new FeeStatusResult(outstanding, currency, lines);
        } catch (AccessDeniedException e) {
            return new ToolError("not authorized to view this student's fees");
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
