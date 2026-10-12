package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.FacultyRegularisation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same isolation reason as {@link FacultyAttendanceInsertGuard}: a second open request for the same faculty member and day
 * violates {@code uq_faculty_regularisation_one_pending_per_day}, which aborts the whole PostgreSQL transaction, so the insert
 * (and its audit row and outbox event) runs in its own REQUIRES_NEW transaction and lets the constraint exception escape.
 */
@Component
public class FacultyRegularisationInsertGuard {

    private final FacultyRegularisationRepository repository;

    public FacultyRegularisationInsertGuard(FacultyRegularisationRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(FacultyRegularisation row, Runnable sameTransactionWork) {
        repository.saveAndFlush(row);
        sameTransactionWork.run();
    }
}
