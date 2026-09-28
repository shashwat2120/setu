package com.setu.directory.infrastructure;

import com.setu.directory.domain.StudentGuardian;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy duplicate-relationship INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: {@code
 * StudentGuardianService}'s link method previously called {@code
 * studentGuardianRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a genuine duplicate relationship would
 * poison that connection's transaction even though the Java exception was
 * caught, turning the intended {@code LinkResult.DuplicateRelationship}
 * into an unhandled 500 at commit.
 */
@Component
public class StudentGuardianInsertGuard {

    private final StudentGuardianRepository studentGuardianRepository;

    public StudentGuardianInsertGuard(StudentGuardianRepository studentGuardianRepository) {
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(StudentGuardian link) {
        studentGuardianRepository.saveAndFlush(link);
    }
}
