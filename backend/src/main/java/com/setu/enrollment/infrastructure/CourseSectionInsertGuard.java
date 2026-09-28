package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.CourseSection;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy section-code-uniqueness INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: {@code
 * CourseSectionService#create} previously called {@code
 * courseSectionRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a duplicate code would poison that
 * connection's transaction even though the Java exception was caught,
 * turning the intended {@code CreateResult.SectionCodeAlreadyInUse} into an
 * unhandled 500 at commit.
 */
@Component
public class CourseSectionInsertGuard {

    private final CourseSectionRepository courseSectionRepository;

    public CourseSectionInsertGuard(CourseSectionRepository courseSectionRepository) {
        this.courseSectionRepository = courseSectionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(CourseSection section) {
        courseSectionRepository.saveAndFlush(section);
    }
}
