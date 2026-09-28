package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.Program;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy program-code-uniqueness INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: {@code
 * ProgramService#create} previously called {@code
 * programRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a duplicate code would poison that
 * connection's transaction even though the Java exception was caught,
 * turning the intended {@code CreateResult.CodeAlreadyInUse} into an
 * unhandled 500 at commit.
 */
@Component
public class ProgramInsertGuard {

    private final ProgramRepository programRepository;

    public ProgramInsertGuard(ProgramRepository programRepository) {
        this.programRepository = programRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Program program) {
        programRepository.saveAndFlush(program);
    }
}
