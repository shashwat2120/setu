package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.StudentProgram;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy duplicate-active-primary-program INSERT in its own
 * {@code REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction
 * defense as {@code AssessmentComponentInsertGuard} (see its javadoc for
 * the full explanation). Added Sprint 5D hardening follow-up: {@code
 * StudentProgramService#assign} previously called {@code
 * studentProgramRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a genuine duplicate would poison that
 * connection's transaction even though the Java exception was caught,
 * turning the intended {@code AssignResult.DuplicateActivePrimaryProgram}
 * into an unhandled 500 at commit.
 */
@Component
public class StudentProgramInsertGuard {

    private final StudentProgramRepository studentProgramRepository;

    public StudentProgramInsertGuard(StudentProgramRepository studentProgramRepository) {
        this.studentProgramRepository = studentProgramRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(StudentProgram studentProgram) {
        studentProgramRepository.saveAndFlush(studentProgram);
    }
}
