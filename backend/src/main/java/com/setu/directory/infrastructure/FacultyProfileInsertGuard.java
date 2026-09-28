package com.setu.directory.infrastructure;

import com.setu.directory.domain.FacultyProfile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy duplicate-employee-number INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: {@code
 * FacultyProfileService#create} previously called {@code
 * facultyProfileRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a genuine duplicate would poison that
 * connection's transaction even though the Java exception was caught,
 * turning the intended {@code CreateResult.DuplicateEmployeeNo} into an
 * unhandled 500 at commit.
 */
@Component
public class FacultyProfileInsertGuard {

    private final FacultyProfileRepository facultyProfileRepository;

    public FacultyProfileInsertGuard(FacultyProfileRepository facultyProfileRepository) {
        this.facultyProfileRepository = facultyProfileRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(FacultyProfile faculty) {
        facultyProfileRepository.saveAndFlush(faculty);
    }
}
