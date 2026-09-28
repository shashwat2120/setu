package com.setu.finance.infrastructure;

import com.setu.finance.domain.StudentAccount;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy {@code (tenant_id, student_id)}-uniqueness INSERT for
 * the get-or-create account flow in its own {@code REQUIRES_NEW}
 * transaction — same PostgreSQL poisoned-transaction defense as {@code
 * AssessmentComponentInsertGuard}. Needed here specifically because the
 * very first charge or payment for a student races to create their one
 * account row; every subsequent call finds it already exists.
 */
@Component
public class StudentAccountInsertGuard {

    private final StudentAccountRepository studentAccountRepository;

    public StudentAccountInsertGuard(StudentAccountRepository studentAccountRepository) {
        this.studentAccountRepository = studentAccountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(StudentAccount account) {
        studentAccountRepository.saveAndFlush(account);
    }
}
