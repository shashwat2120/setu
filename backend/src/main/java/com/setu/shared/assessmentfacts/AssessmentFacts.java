package com.setu.shared.assessmentfacts;

import com.setu.shared.internalcall.DependencyUnavailableException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the risk engine, the AI advisor and the academic record (transcripts, GPA) need from assessment: a student's current grades, with the name and weight of what each is a
 * grade for, and the assessments of a section that are visible to students. A port, so that they do not read the {@code assessment} schema: in the monolith the assessment module
 * answers in-process ({@code LocalAssessmentFacts}); once Assessment has a database of its own the answer comes from the Assessment service's internal endpoints
 * ({@link RemoteAssessmentFacts}, switched on by {@code setu.assessment.url}).
 * <p>
 * No authorization of its own, like the methods it replaces ({@code GradeService.findCurrentForEnrollment}, {@code AssessmentComponentService.weightsUnchecked},
 * {@code AssessmentService.listVisibleForSection}): the caller has already authorized its outer operation. The remote one carries the service-to-service token and the
 * endpoints are not routed by the gateway.
 */
public interface AssessmentFacts {

    /** One current (published, latest version) grade of an enrollment. The title, code and weight are those of the component it grades; the title is null if it has none. */
    record GradeFact(UUID enrollmentId, UUID componentId, BigDecimal rawMarks, String gradeCode, BigDecimal gradePoint,
                     String assessmentTitle, String componentCode, BigDecimal weight) {
    }

    /** An assessment of a section that is visible to students (not draft, not merely scheduled). */
    record VisibleAssessment(UUID id, String title, Instant startsAt) {
    }

    /**
     * The current grades of each enrollment asked about; an enrollment with none is in the answer with an empty list.
     *
     * @throws Unavailable if the owner could not be asked or answered something unusable: never a guess, never an empty list standing in for an answer
     */
    Map<UUID, List<GradeFact>> currentGrades(Collection<UUID> enrollmentIds);

    /** The student-visible assessments of each section asked about; a section with none is in the answer with an empty list. @throws Unavailable as above */
    Map<UUID, List<VisibleAssessment>> visibleAssessments(Collection<UUID> sectionIds);

    /** The owner of the data could not give an answer. Callers say so (retry the event, tell the person) and never substitute data. */
    class Unavailable extends DependencyUnavailableException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
