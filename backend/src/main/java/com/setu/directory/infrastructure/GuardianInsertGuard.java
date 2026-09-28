package com.setu.directory.infrastructure;

import com.setu.directory.domain.Guardian;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy one-profile-per-person-per-tenant INSERT in its own
 * {@code REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction
 * defense as {@code AssessmentComponentInsertGuard} (see its javadoc for
 * the full explanation). Added Sprint 5D hardening follow-up: {@code
 * GuardianProfileService#create} previously called {@code
 * guardianRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a genuine duplicate would poison that
 * connection's transaction even though the Java exception was caught,
 * turning the intended {@code
 * CreateResult.PersonAlreadyHasProfileInTenant} into an unhandled 500 at
 * commit.
 */
@Component
public class GuardianInsertGuard {

    private final GuardianRepository guardianRepository;

    public GuardianInsertGuard(GuardianRepository guardianRepository) {
        this.guardianRepository = guardianRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Guardian guardian) {
        guardianRepository.saveAndFlush(guardian);
    }
}
