package com.setu.finance.infrastructure;

import com.setu.finance.domain.FeeComponent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates the racy {@code (fee_structure_id, code)}-uniqueness INSERT in its own {@code REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense as {@code AssessmentComponentInsertGuard} (see its javadoc for the full explanation). */
@Component
public class FeeComponentInsertGuard {

    private final FeeComponentRepository feeComponentRepository;

    public FeeComponentInsertGuard(FeeComponentRepository feeComponentRepository) {
        this.feeComponentRepository = feeComponentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(FeeComponent component) {
        feeComponentRepository.saveAndFlush(component);
    }
}
