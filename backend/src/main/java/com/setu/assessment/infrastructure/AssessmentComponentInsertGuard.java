package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.AssessmentComponent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy component-code-uniqueness INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@link AssessmentAttemptInsertGuard}/{@link AssessmentResponseInsertGuard}.
 * <p>
 * Added Sprint 5D (post-Day 11 exception-handling audit): {@code
 * AssessmentComponentService#create} always caught {@code
 * DataIntegrityViolationException} from a duplicate code, but did so
 * directly against its own {@code @Transactional} method's connection — a
 * failed flush aborts that PostgreSQL transaction outright, so even though
 * the Java exception was caught, the surrounding {@code @Transactional}
 * method's own commit at method exit then failed with {@code
 * UnexpectedRollbackException}, silently turning the intended, already-
 * correctly-typed {@code CreateResult.CodeAlreadyInUse} outcome into an
 * unhandled 500. Every prior test exercising this path was itself {@code
 * @Transactional} (rolled back at test end regardless, before ever
 * attempting the real commit that exposes the bug) — a real, committing
 * HTTP request is what surfaced it, during this task's own regression
 * testing of existing typed-error behavior.
 */
@Component
public class AssessmentComponentInsertGuard {

    private final AssessmentComponentRepository assessmentComponentRepository;

    public AssessmentComponentInsertGuard(AssessmentComponentRepository assessmentComponentRepository) {
        this.assessmentComponentRepository = assessmentComponentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AssessmentComponent component) {
        assessmentComponentRepository.saveAndFlush(component);
    }
}
