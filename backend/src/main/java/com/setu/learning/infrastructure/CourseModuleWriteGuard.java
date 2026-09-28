package com.setu.learning.infrastructure;

import com.setu.learning.domain.CourseModule;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy sequence-uniqueness WRITE (both the initial INSERT and
 * a later UPDATE that changes sequence) in its own {@code REQUIRES_NEW}
 * transaction — same PostgreSQL poisoned-transaction defense as {@code
 * AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: both {@code
 * CourseModuleService#create} and {@code CourseModuleService#update}
 * previously called {@code courseModuleRepository.saveAndFlush} directly
 * against their own {@code @Transactional} connection, so a genuine
 * sequence collision would poison that connection's transaction even
 * though the Java exception was caught, turning the intended {@code
 * SequenceAlreadyInUse} outcome (named in both {@code CreateResult} and
 * {@code UpdateResult}) into an unhandled 500 at commit. One shared
 * {@code saveAndFlush} method serves both call sites — JPA's {@code
 * saveAndFlush} already handles insert vs. update transparently based on
 * the entity's own persistence state.
 */
@Component
public class CourseModuleWriteGuard {

    private final CourseModuleRepository courseModuleRepository;

    public CourseModuleWriteGuard(CourseModuleRepository courseModuleRepository) {
        this.courseModuleRepository = courseModuleRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAndFlush(CourseModule module) {
        courseModuleRepository.saveAndFlush(module);
    }
}
