package com.setu.finance.application;

import com.setu.directory.application.StudentProfileService;
import com.setu.finance.domain.FeeComponent;
import com.setu.finance.domain.StudentCharge;
import com.setu.finance.domain.StudentChargeStatus;
import com.setu.finance.infrastructure.StudentChargeRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 12 §Finance: student_charges — a student's actual obligation for one
 * {@link FeeComponent}. Creating a charge increases the student's {@code
 * student_accounts.balance} atomically in the SAME transaction (via {@link
 * StudentAccountService#adjustBalance}); amount/currency are copied from
 * the fee component at creation time, never re-read live later.
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class StudentChargeService {

    private static final String MANAGE_PERMISSION = "MANAGE_FINANCE";
    private static final String VIEW_PERMISSION_STUDENT = "VIEW_FINANCE";

    private final StudentChargeRepository studentChargeRepository;
    private final FeeComponentService feeComponentService;
    private final StudentAccountService studentAccountService;
    private final StudentProfileService studentProfileService;
    private final FinanceAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public StudentChargeService(StudentChargeRepository studentChargeRepository, FeeComponentService feeComponentService,
                                 StudentAccountService studentAccountService, StudentProfileService studentProfileService,
                                 FinanceAccessSupport accessSupport, AuthorizationContext authorizationContext,
                                 AuditLogWriter auditLogWriter) {
        this.studentChargeRepository = studentChargeRepository;
        this.feeComponentService = feeComponentService;
        this.studentAccountService = studentAccountService;
        this.studentProfileService = studentProfileService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID chargeId) implements CreateResult {
        }

        record FeeComponentNotFound() implements CreateResult {
        }

        record StudentNotFound() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID studentId, UUID feeComponentId, LocalDate dueDate) {
        Optional<FeeComponent> maybeComponent = feeComponentService.findById(feeComponentId);
        if (maybeComponent.isEmpty()) {
            return new CreateResult.FeeComponentNotFound();
        }
        FeeComponent component = maybeComponent.get();

        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return new CreateResult.StudentNotFound();
        }
        UUID tenantId = studentInfo.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_charge", UUID.randomUUID(), tenantId));

        StudentCharge charge = new StudentCharge(UUID.randomUUID(), tenantId, studentId, feeComponentId,
            component.amount(), component.currency(), dueDate);
        studentChargeRepository.saveAndFlush(charge);

        var account = studentAccountService.getOrCreate(tenantId, studentId, component.currency());
        studentAccountService.adjustBalance(account.id(), component.amount());

        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "STUDENT_CHARGE_CREATED",
            "finance.student_charges", charge.id(), Map.of("studentId", studentId, "amount", component.amount()));
        return new CreateResult.Success(charge.id());
    }

    public sealed interface TransitionResult {
        record Success() implements TransitionResult {
        }

        record NotFound() implements TransitionResult {
        }

        record InvalidTransition() implements TransitionResult {
        }
    }

    @Transactional
    public TransitionResult markWaived(UUID chargeId) {
        return transition(chargeId, StudentChargeStatus.WAIVED, "STUDENT_CHARGE_WAIVED");
    }

    @Transactional
    public TransitionResult markOverdue(UUID chargeId) {
        return transition(chargeId, StudentChargeStatus.OVERDUE, "STUDENT_CHARGE_OVERDUE");
    }

    @Transactional
    public TransitionResult markPaid(UUID chargeId) {
        return transition(chargeId, StudentChargeStatus.PAID, "STUDENT_CHARGE_PAID");
    }

    private TransitionResult transition(UUID chargeId, StudentChargeStatus target, String auditAction) {
        Optional<StudentCharge> maybeCharge = studentChargeRepository.findById(chargeId);
        if (maybeCharge.isEmpty()) {
            return new TransitionResult.NotFound();
        }
        StudentCharge charge = maybeCharge.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_charge", charge.id(), charge.tenantId()));
        if (!charge.canTransitionTo(target)) {
            return new TransitionResult.InvalidTransition();
        }
        int updated = studentChargeRepository.transitionIfStatus(chargeId, charge.status(), target);
        if (updated == 0) {
            return new TransitionResult.InvalidTransition();
        }
        auditLogWriter.write(charge.tenantId(), authorizationContext.currentUserId(), auditAction,
            "finance.student_charges", charge.id(), Map.of("target", target.name()));
        return new TransitionResult.Success();
    }

    /** Own (student, VIEW_FINANCE — also guardian-delegatable) or admin (MANAGE_FINANCE). */
    @Transactional(readOnly = true)
    public List<StudentCharge> listForStudent(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return List.of();
        }
        assertReadAccess(studentInfo.get().tenantId(), studentId, studentInfo.get().ownerUserId());
        return studentChargeRepository.findByStudentId(studentId);
    }

    private void assertReadAccess(UUID tenantId, UUID studentId, UUID ownerUserId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION_STUDENT,
                ResourceRef.of("student_charge", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        if (authorizationContext.can(MANAGE_PERMISSION, ResourceRef.of("student_charge", studentId, tenantId)).isAllowed()) {
            return;
        }
        ResourceRef delegated = ResourceRef.of("student_charge", studentId, tenantId)
            .ownedBy(ownerUserId).delegatableForStudent(studentId);
        authorizationContext.assertCan(VIEW_PERMISSION_STUDENT, delegated);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID studentId, UUID feeComponentId, java.time.LocalDate dueDate) {
        return switch (create(studentId, feeComponentId, dueDate)) {
            case CreateResult.Success s -> s.chargeId();
            default -> throw new IllegalStateException("seed student charge creation failed for student: " + studentId);
        };
    }

}
