package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.Course;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy course-code-uniqueness INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: {@code
 * CourseService#create} previously called {@code
 * courseRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a duplicate code would poison that
 * connection's transaction even though the Java exception was caught,
 * turning the intended {@code CreateResult.CodeAlreadyInUse} into an
 * unhandled 500 at commit.
 */
@Component
public class CourseInsertGuard {

    private final CourseRepository courseRepository;

    public CourseInsertGuard(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Course course) {
        courseRepository.saveAndFlush(course);
    }
}
