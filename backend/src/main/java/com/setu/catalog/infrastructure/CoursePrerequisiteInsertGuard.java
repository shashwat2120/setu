package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.CoursePrerequisite;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy duplicate-prerequisite-edge INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: {@code
 * CoursePrerequisiteService#add} previously called {@code
 * prerequisiteRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a genuine duplicate edge would poison
 * that connection's transaction even though the Java exception was
 * caught, turning the intended {@code AddResult.DuplicateEdge} into an
 * unhandled 500 at commit.
 */
@Component
public class CoursePrerequisiteInsertGuard {

    private final CoursePrerequisiteRepository prerequisiteRepository;

    public CoursePrerequisiteInsertGuard(CoursePrerequisiteRepository prerequisiteRepository) {
        this.prerequisiteRepository = prerequisiteRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(CoursePrerequisite edge) {
        prerequisiteRepository.saveAndFlush(edge);
    }
}
