package com.setu.career.infrastructure;

import com.setu.career.domain.Application;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy {@code (job_id, student_id)}-uniqueness INSERT — Day
 * 12's "duplicate applications must be prevented safely" /
 * "concurrent application attempts must be safe" requirement — in its own
 * {@code REQUIRES_NEW} transaction, same PostgreSQL poisoned-transaction
 * defense as {@code AssessmentComponentInsertGuard}.
 */
@Component
public class ApplicationInsertGuard {

    private final ApplicationRepository applicationRepository;

    public ApplicationInsertGuard(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Application application) {
        applicationRepository.saveAndFlush(application);
    }
}
