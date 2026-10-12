package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.StaffAttendancePolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same isolation reason as {@link FacultyAttendanceInsertGuard}: two administrators saving the same next version violate
 * {@code uq_staff_attendance_policies_tenant_version}, which aborts the whole PostgreSQL transaction, so the insert (with its audit
 * row and outbox event) runs in its own REQUIRES_NEW transaction and lets the constraint exception escape for the caller to report.
 */
@Component
public class StaffAttendancePolicyInsertGuard {

    private final StaffAttendancePolicyRepository repository;

    public StaffAttendancePolicyInsertGuard(StaffAttendancePolicyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(StaffAttendancePolicy policy, Runnable sameTransactionWork) {
        repository.saveAndFlush(policy);
        sameTransactionWork.run();
    }
}
