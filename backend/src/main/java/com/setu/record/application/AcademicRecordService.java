package com.setu.record.application;

import com.setu.shared.assessmentfacts.AssessmentFacts;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.academic.RecordDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
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
 * Degree progress is a single completed-credits/required-credits ratio, not a per-requirement-group breakdown. Credits count when a course is
 * <b>passed</b>: its enrollment is completed (its final grade was published) and that grade is not an F. A failed course is on the transcript, in
 * the GPA at its grade points, and earns nothing toward the degree.
 * <p>
 * Everything this class needs from Academic (a student's enrollments and their courses, the student's curriculum and its required credits, who
 * owns a student profile) comes through two narrow shared ports, {@link RecordDirectory} and {@link AcademicDirectory}, and from Assessment's
 * own grades and components. It imports none of Academic's services or classes, which is what lets it move into the Assessment service (the
 * coupling audit folds Record into Assessment) with those ports answered from a local copy of Academic's data.
 */
@org.springframework.modulith.NamedInterface // the AI advisor's transcript/GPA tool reads this; both methods authorize the caller themselves
@Service
public class AcademicRecordService {

    private static final String ADMIN_VIEW_PERMISSION = "VIEW_TENANT_DATA";
    private static final String DELEGATED_PERMISSION = "VIEW_ACADEMIC_STATUS";

    private final RecordDirectory recordDirectory;
    private final AcademicDirectory academicDirectory;
    private final AssessmentFacts assessmentFacts;
    private final AuthorizationContext authorizationContext;

    public AcademicRecordService(RecordDirectory recordDirectory, AcademicDirectory academicDirectory, AssessmentFacts assessmentFacts,
                                  AuthorizationContext authorizationContext) {
        this.recordDirectory = recordDirectory;
        this.academicDirectory = academicDirectory;
        this.assessmentFacts = assessmentFacts;
        this.authorizationContext = authorizationContext;
    }

    public record GradeLine(UUID componentId, BigDecimal rawMarks, String gradeCode, BigDecimal gradePoint) {
    }

    /** {@code courseGradePoint} is the weighted rollup of {@code grades} (null when nothing is published yet) - see the class javadoc. */
    public record TranscriptLine(UUID enrollmentId, String courseCode, String courseTitle, BigDecimal credits,
                                  String sectionCode, String enrollmentStatus, List<GradeLine> grades,
                                  BigDecimal courseGradePoint, String finalGrade) {
    }

    public record Transcript(List<TranscriptLine> lines, BigDecimal gpa) {
    }

    @Transactional(readOnly = true)
    public Transcript transcript(UUID studentId, UUID tenantId) {
        assertRead(studentId, tenantId);
        return buildTranscript(studentId);
    }

    /**
     * The credit-weighted GPA to date (null if nothing is graded), with NO authorization of its own: for {@code shared.career.StudentStandings}, whose caller (a
     * student applying for themselves, an officer previewing eligibility) has already authorized its own operation, and for whom the checked {@link #transcript}
     * would wrongly refuse a placement officer.
     */
    @Transactional(readOnly = true)
    public BigDecimal gpaUnchecked(UUID studentId) {
        return buildTranscript(studentId).gpa();
    }

    private Transcript buildTranscript(UUID studentId) {
        List<TranscriptLine> lines = new java.util.ArrayList<>();
        List<RecordDirectory.CourseEnrollment> enrollments = recordDirectory.enrollmentsOf(studentId);
        // the grades and the weight of what each grades, from the owner of grades, in one call; if it cannot answer, no transcript is built from guesses
        var gradesByEnrollment = assessmentFacts.currentGrades(enrollments.stream().map(RecordDirectory.CourseEnrollment::enrollmentId).toList());
        for (RecordDirectory.CourseEnrollment e : enrollments) {
            var grades = gradesByEnrollment.get(e.enrollmentId());
            List<GradeLine> gradeLines = grades.stream()
                .map(g -> new GradeLine(g.componentId(), g.rawMarks(), g.gradeCode(), g.gradePoint())).toList();
            java.util.Map<UUID, BigDecimal> weights = new java.util.HashMap<>();
            grades.forEach(g -> weights.put(g.componentId(), g.weight()));
            lines.add(new TranscriptLine(e.enrollmentId(), e.courseCode(), e.courseTitle(), e.credits(), e.sectionCode(),
                e.status(), gradeLines, courseGradePoint(gradeLines, weights), e.finalGrade()));
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
        // assertRead above already authorized this caller; the directory's lookups carry no authorization of their own, so the
        // registrar-only permission that managing a student's programs demands does not lock the student out of their own degree progress.
        Optional<UUID> activeCurriculumId = recordDirectory.activeCurriculumOf(studentId);
        if (activeCurriculumId.isEmpty()) {
            return new DegreeProgress(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        BigDecimal required = recordDirectory.requiredCredits(activeCurriculumId.get());

        BigDecimal completed = BigDecimal.ZERO;
        for (RecordDirectory.CourseEnrollment e : recordDirectory.enrollmentsOf(studentId)) {
            if (e.passed() && e.credits() != null) {
                completed = completed.add(e.credits());
            }
        }
        BigDecimal percentage = required.signum() > 0
            ? completed.divide(required, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        return new DegreeProgress(completed, required, percentage, true);
    }

    private void assertRead(UUID studentId, UUID tenantId) {
        UUID currentUserId = authorizationContext.currentUserId();
        Optional<AcademicDirectory.TenantOwner> owner = academicDirectory.studentOwner(studentId);
        boolean isSelf = owner.isPresent() && currentUserId.equals(owner.get().ownerUserId());
        if (isSelf) {
            return;
        }
        if (authorizationContext.can(ADMIN_VIEW_PERMISSION, ResourceRef.of("academic_record", studentId, tenantId)).isAllowed()) {
            return;
        }
        UUID ownerUserId = owner.map(AcademicDirectory.TenantOwner::ownerUserId).orElse(null);
        ResourceRef delegated = ResourceRef.of("academic_record", studentId, tenantId)
            .ownedBy(ownerUserId).delegatableForStudent(studentId);
        authorizationContext.assertCan(DELEGATED_PERMISSION, delegated);
    }
}
