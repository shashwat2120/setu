package com.setu.finance.application;

import com.setu.finance.domain.StudentAccount;
import com.setu.finance.infrastructure.StudentAccountInsertGuard;
import com.setu.finance.infrastructure.StudentAccountRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 12 §Finance: student_accounts — the running-balance account. {@link
 * #getOrCreate} and {@link #adjustBalance} are package-private: only {@code
 * StudentChargeService} and {@code PaymentService} touch balances directly,
 * every other caller reads through {@link #get}.
 */
@Service
public class StudentAccountService {

    private static final String VIEW_PERMISSION_STUDENT = "VIEW_FINANCE";
    private static final String VIEW_PERMISSION_ADMIN = "MANAGE_FINANCE";
    private static final String VIEW_PERMISSION_ADMIN_READ = "VIEW_TENANT_DATA";

    private final StudentAccountRepository studentAccountRepository;
    private final StudentAccountInsertGuard studentAccountInsertGuard;
    private final FinanceAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;

    public StudentAccountService(StudentAccountRepository studentAccountRepository, StudentAccountInsertGuard studentAccountInsertGuard,
                                  FinanceAccessSupport accessSupport, AuthorizationContext authorizationContext) {
        this.studentAccountRepository = studentAccountRepository;
        this.studentAccountInsertGuard = studentAccountInsertGuard;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
    }

    /** Get-or-create — the account row is lazily materialized on first charge/payment, never pre-provisioned. Package-private: no authorization of its own, callers (StudentChargeService/PaymentService) have already authorized their own write. */
    StudentAccount getOrCreate(UUID tenantId, UUID studentId, String currency) {
        Optional<StudentAccount> existing = studentAccountRepository.findByTenantIdAndStudentId(tenantId, studentId);
        if (existing.isPresent()) {
            return existing.get();
        }
        StudentAccount account = new StudentAccount(UUID.randomUUID(), tenantId, studentId, currency);
        try {
            studentAccountInsertGuard.insert(account);
            return account;
        } catch (DataIntegrityViolationException e) {
            // Lost the create race — the winner's row now exists; fall through and read it.
            return studentAccountRepository.findByTenantIdAndStudentId(tenantId, studentId).orElseThrow();
        }
    }

    /** The only way balance is ever mutated — see StudentAccountRepository#adjustBalance's javadoc. Package-private, no authorization of its own (same reason as getOrCreate). */
    void adjustBalance(UUID accountId, BigDecimal delta) {
        studentAccountRepository.adjustBalance(accountId, delta);
    }

    /** Own (student, VIEW_FINANCE — also guardian-delegatable), admin (MANAGE_FINANCE or VIEW_TENANT_DATA). */
    @Transactional(readOnly = true)
    public Optional<StudentAccount> get(UUID tenantId, UUID studentId) {
        Optional<StudentAccount> account = studentAccountRepository.findByTenantIdAndStudentId(tenantId, studentId);
        assertReadAccess(tenantId, studentId);
        return account;
    }

    void assertReadAccess(UUID tenantId, UUID studentId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION_STUDENT,
                ResourceRef.of("student_account", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        if (authorizationContext.can(VIEW_PERMISSION_ADMIN, ResourceRef.of("student_account", studentId, tenantId)).isAllowed()
            || authorizationContext.can(VIEW_PERMISSION_ADMIN_READ, ResourceRef.of("student_account", studentId, tenantId)).isAllowed()) {
            return;
        }
        UUID ownerUserId = accessSupport.ownerUserIdOf(studentId).orElse(null);
        ResourceRef delegated = ResourceRef.of("student_account", studentId, tenantId)
            .ownedBy(ownerUserId).delegatableForStudent(studentId);
        authorizationContext.assertCan(VIEW_PERMISSION_STUDENT, delegated);
    }
}
