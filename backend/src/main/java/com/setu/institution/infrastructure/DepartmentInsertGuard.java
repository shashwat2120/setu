package com.setu.institution.infrastructure;

import com.setu.institution.domain.Department;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy department-code-uniqueness INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: {@code
 * DepartmentService#create} previously called {@code
 * departmentRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a duplicate code would poison that
 * connection's transaction even though the Java exception was caught,
 * turning the intended {@code CreateResult.CodeAlreadyInUse} into an
 * unhandled 500 at commit.
 */
@Component
public class DepartmentInsertGuard {

    private final DepartmentRepository departmentRepository;

    public DepartmentInsertGuard(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Department department) {
        departmentRepository.saveAndFlush(department);
    }
}
