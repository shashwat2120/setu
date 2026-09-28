package com.setu.finance.application;

import com.setu.finance.domain.Payment;
import com.setu.finance.domain.Receipt;
import com.setu.finance.infrastructure.PaymentRepository;
import com.setu.finance.infrastructure.ReceiptRepository;
import com.setu.finance.infrastructure.StudentAccountRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Day 12 §Finance: receipts — read-only from the application layer (issuance happens inline inside {@code PaymentService#recordWebhookEvent} on a SUCCEEDED transition, always in the same transaction as the balance adjustment it documents). */
@Service
public class ReceiptService {

    private static final String VIEW_PERMISSION_STUDENT = "VIEW_FINANCE";
    private static final String VIEW_PERMISSION_ADMIN = "MANAGE_FINANCE";

    private final ReceiptRepository receiptRepository;
    private final PaymentRepository paymentRepository;
    private final StudentAccountRepository studentAccountRepository;
    private final FinanceAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;

    public ReceiptService(ReceiptRepository receiptRepository, PaymentRepository paymentRepository,
                           StudentAccountRepository studentAccountRepository, FinanceAccessSupport accessSupport,
                           AuthorizationContext authorizationContext) {
        this.receiptRepository = receiptRepository;
        this.paymentRepository = paymentRepository;
        this.studentAccountRepository = studentAccountRepository;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
    }

    @Transactional(readOnly = true)
    public Optional<Receipt> getForPayment(UUID paymentId) {
        Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
        if (maybePayment.isEmpty()) {
            return Optional.empty();
        }
        Payment payment = maybePayment.get();
        UUID studentId = studentAccountRepository.findById(payment.studentAccountId())
            .map(com.setu.finance.domain.StudentAccount::studentId).orElse(null);
        assertReadAccess(payment.tenantId(), studentId);
        return receiptRepository.findByPaymentId(paymentId);
    }

    private void assertReadAccess(UUID tenantId, UUID studentId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION_STUDENT,
                ResourceRef.of("receipt", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        authorizationContext.assertCan(VIEW_PERMISSION_ADMIN, ResourceRef.of("receipt", studentId, tenantId));
    }
}
