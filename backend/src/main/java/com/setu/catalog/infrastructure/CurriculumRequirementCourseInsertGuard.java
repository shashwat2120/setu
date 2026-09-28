package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.CurriculumRequirementCourse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy duplicate-requirement-course-mapping INSERT in its own
 * {@code REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction
 * defense as {@code AssessmentComponentInsertGuard} (see its javadoc for
 * the full explanation). Added Sprint 5D hardening follow-up: {@code
 * CurriculumRequirementService#addCourse} previously called {@code
 * requirementCourseRepository.saveAndFlush} directly against its own
 * {@code @Transactional} connection, so a genuine duplicate mapping would
 * poison that connection's transaction even though the Java exception was
 * caught, turning the intended {@code AddCourseResult.DuplicateMapping}
 * into an unhandled 500 at commit.
 */
@Component
public class CurriculumRequirementCourseInsertGuard {

    private final CurriculumRequirementCourseRepository requirementCourseRepository;

    public CurriculumRequirementCourseInsertGuard(CurriculumRequirementCourseRepository requirementCourseRepository) {
        this.requirementCourseRepository = requirementCourseRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(CurriculumRequirementCourse link) {
        requirementCourseRepository.saveAndFlush(link);
    }
}
