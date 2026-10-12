package com.setu.record.application;

import com.setu.assessment.application.AssessmentComponentService;
import com.setu.assessment.application.GradeService;
import com.setu.assessment.domain.Grade;
import com.setu.catalog.application.CourseService;
import com.setu.catalog.application.CurriculumRequirementService;
import com.setu.catalog.domain.Course;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.application.CourseSectionService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.application.StudentProgramService;
import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.Enrollment;
import com.setu.enrollment.domain.EnrollmentStatus;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Academic record, GPA, transcript and degree progress - computed directly from the authoritative
 * enrollment/grade/curriculum records at read time, never a stored snapshot. Matches the canonical spec's own
 * wording for this module ("transcripts generated from authoritative records") - no new persistent aggregate was
 * needed, which kept this a same-day addition instead of a multi-migration one.
 * <p>
 * GPA is credit-weighted, built in two steps (the rule is stated here because the codebase had none):
 * <ol>
 *   <li><b>Course grade point</b> = the average of that enrollment's current published component grade points,
 *       weighted by each component's configured {@code weight}. A component without a usable weight counts as 1.</li>
 *   <li><b>GPA</b> = sum(course grade point x course credits) / sum(credits), over courses that have at least one
 *       published grade and known credits. Courses with no published grade yet are left out rather than counted as
 *       zero, so this is a GPA "to date", not a final one.</li>
 * </ol>
 * Degree progress is a single completed-credits/required-credits ratio, not a per-requirement-group breakdown.
 */
@org.springframework.modulith.NamedInterface // the AI advisor's transcript/GPA tool reads this; both methods authorize the caller themselves
@Service
public class AcademicRecordService {

    private static final String ADMIN_VIEW_PERMISSION = "VIEW_TENANT_DATA";
    private static final String DELEGATED_PERMISSION = "VIEW_ACADEMIC_STATUS";

    private final EnrollmentService enrollmentService;
    private final CourseSectionService courseSectionService;
    private final CourseService courseService;
    private final GradeService gradeService;
    private final StudentProfileService studentProfileService;
    private final StudentProgramService studentProgramService;
    private final CurriculumRequirementService curriculumRequirementService;
    private final AuthorizationContext authorizationContext;
    private final AssessmentComponentService assessmentComponentService;

    public AcademicRecordService(EnrollmentService enrollmentService, CourseSectionService courseSectionService,
                                  CourseService courseService, GradeService gradeService,
                                  StudentProfileService studentProfileService, StudentProgramService studentProgramService,
                                  CurriculumRequirementService curriculumRequirementService,
                                  AuthorizationContext authorizationContext,
                                  AssessmentComponentService assessmentComponentService) {
        this.assessmentComponentService = assessmentComponentService;
        this.enrollmentService = enrollmentService;
        this.courseSectionService = courseSectionService;
        this.courseService = courseService;
        this.gradeService = gradeService;
        this.studentProfileService = studentProfileService;
        this.studentProgramService = studentProgramService;
        this.curriculumRequirementService = curriculumRequirementService;
        this.authorizationContext = authorizationContext;
    }

    public record GradeLine(UUID componentId, BigDecimal rawMarks, String gradeCode, BigDecimal gradePoint) {
    }

    /** {@code courseGradePoint} is the weighted rollup of {@code grades} (null when nothing is published yet) - see the class javadoc. */
    public record TranscriptLine(UUID enrollmentId, String courseCode, String courseTitle, BigDecimal credits,
                                  String sectionCode, String enrollmentStatus, List<GradeLine> grades,
                                  BigDecimal courseGradePoint) {
    }

    public record Transcript(List<TranscriptLine> lines, BigDecimal gpa) {
    }

    @Transactional(readOnly = true)
    public Transcript transcript(UUID studentId, UUID tenantId) {
        assertRead(studentId, tenantId);
        var enrollments = enrollmentService.listForStudent(studentId, PageRequest.of(0, 200)).getContent();

        List<TranscriptLine> lines = new java.util.ArrayList<>();
        for (Enrollment e : enrollments) {
            Optional<CourseSection> section = courseSectionService.get(e.sectionId());
            Optional<Course> course = section.flatMap(s -> courseService.get(s.courseId()));
            List<Grade> grades = gradeService.findCurrentForEnrollment(e.id());
            List<GradeLine> gradeLines = grades.stream()
                .map(g -> new GradeLine(g.componentId(), g.rawMarks(), g.gradeCode(), g.gradePoint())).toList();
            var weights = assessmentComponentService.weightsUnchecked(gradeLines.stream().map(GradeLine::componentId).toList());
            lines.add(new TranscriptLine(e.id(), course.map(Course::code).orElse(null), course.map(Course::title).orElse(null),
                course.map(Course::credits).orElse(null), section.map(CourseSection::sectionCode).orElse(null),
                e.status().name(), gradeLines, courseGradePoint(gradeLines, weights)));
        }
        return new Transcript(lines, creditWeightedGpa(lines));
    }

    /** Weighted average of the published component grade points; null if none. A missing or non-positive weight counts as 1. */
    static BigDecimal courseGradePoint(List<GradeLine> grades, java.util.Map<UUID, BigDecimal> weights) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (GradeLine g : grades) {
            if (g.gradePoint() == null) {
                continue;
            }
            BigDecimal w = weights.get(g.componentId());
            if (w == null || w.signum() <= 0) {
                w = BigDecimal.ONE;
            }
            weighted = weighted.add(g.gradePoint().multiply(w));
            totalWeight = totalWeight.add(w);
        }
        return totalWeight.signum() == 0 ? null : weighted.divide(totalWeight, 2, RoundingMode.HALF_UP);
    }

    /** sum(courseGradePoint x credits) / sum(credits) over graded courses with known, positive credits; null if there are none. */
    static BigDecimal creditWeightedGpa(List<TranscriptLine> lines) {
        BigDecimal points = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (TranscriptLine l : lines) {
            if (l.courseGradePoint() == null || l.credits() == null || l.credits().signum() <= 0) {
                continue;
            }
            points = points.add(l.courseGradePoint().multiply(l.credits()));
            credits = credits.add(l.credits());
        }
        return credits.signum() == 0 ? null : points.divide(credits, 2, RoundingMode.HALF_UP);
    }

    public record DegreeProgress(BigDecimal creditsCompleted, BigDecimal creditsRequired, BigDecimal percentage,
                                  boolean hasActiveProgram) {
    }

    @Transactional(readOnly = true)
    public DegreeProgress degreeProgress(UUID studentId, UUID tenantId) {
        assertRead(studentId, tenantId);
        // assertRead above already authorized this caller; StudentProgramService#history would additionally demand the
        // registrar-only MANAGE_STUDENT_PROGRAMS and so lock the student out of their own degree progress.
        Optional<UUID> activeCurriculumId = studentProgramService.activeCurriculumIdUnchecked(studentId);
        if (activeCurriculumId.isEmpty()) {
            return new DegreeProgress(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        BigDecimal required = curriculumRequirementService.totalMinCreditsUnchecked(activeCurriculumId.get());

        var enrollments = enrollmentService.listForStudent(studentId, PageRequest.of(0, 200)).getContent();
        BigDecimal completed = BigDecimal.ZERO;
        for (Enrollment e : enrollments) {
            if (e.status() != EnrollmentStatus.COMPLETED) {
                continue;
            }
            Optional<CourseSection> section = courseSectionService.get(e.sectionId());
            Optional<Course> course = section.flatMap(s -> courseService.get(s.courseId()));
            if (course.isPresent() && course.get().credits() != null) {
                completed = completed.add(course.get().credits());
            }
        }
        BigDecimal percentage = required.signum() > 0
            ? completed.divide(required, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        return new DegreeProgress(completed, required, percentage, true);
    }

    private void assertRead(UUID studentId, UUID tenantId) {
        UUID currentUserId = authorizationContext.currentUserId();
        Optional<StudentProfileService.StudentTenantOwner> owner = studentProfileService.findTenantAndOwnerUserId(studentId);
        boolean isSelf = owner.isPresent() && currentUserId.equals(owner.get().ownerUserId());
        if (isSelf) {
            return;
        }
        if (authorizationContext.can(ADMIN_VIEW_PERMISSION, ResourceRef.of("academic_record", studentId, tenantId)).isAllowed()) {
            return;
        }
        UUID ownerUserId = owner.map(StudentProfileService.StudentTenantOwner::ownerUserId).orElse(null);
        ResourceRef delegated = ResourceRef.of("academic_record", studentId, tenantId)
            .ownedBy(ownerUserId).delegatableForStudent(studentId);
        authorizationContext.assertCan(DELEGATED_PERMISSION, delegated);
    }
}
