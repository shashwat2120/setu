package com.setu.finance.application;

import com.setu.finance.domain.Payment;
import com.setu.finance.domain.PaymentFailedEvent;
import com.setu.finance.domain.PaymentRefundedEvent;
import com.setu.finance.domain.PaymentStatus;
import com.setu.finance.domain.PaymentSuccessfulEvent;
import com.setu.finance.domain.Receipt;
import com.setu.finance.infrastructure.PaymentRepository;
import com.setu.finance.infrastructure.ReceiptRepository;
import com.setu.finance.infrastructure.StudentAccountRepository;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What a payment changing state does to the rest of Finance, in one place so the administrator's stand-in endpoint, the signed provider webhook and
 * the provider lookup all do exactly the same thing: the status moves by a conditional UPDATE (so two concurrent confirmations cannot both win), and
 * only the winner adjusts the balance, issues the receipt, settles the fee, writes the audit row and emits the event, all in the caller's transaction.
 *
 * <p>No permission check lives here, by design: each caller has its own authority (an administrator's {@code MANAGE_FINANCE}, or the provider's
 * signature) and this class is package-private so nothing outside Finance's application layer can reach it without one. {@code actor} is the
 * administrator, or null when the provider itself caused the change.
 */
@Component
class PaymentSettlement {

    private final PaymentRepository paymentRepository;
    private final ReceiptRepository receiptRepository;
    private final StudentAccountRepository studentAccountRepository;
    private final StudentAccountService studentAccountService;
    private final StudentChargeService studentChargeService;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    PaymentSettlement(PaymentRepository paymentRepository, ReceiptRepository receiptRepository, StudentAccountRepository studentAccountRepository,
                      StudentAccountService studentAccountService, StudentChargeService studentChargeService, AuditLogWriter auditLogWriter,
                      OutboxEventWriter outboxEventWriter) {
        this.paymentRepository = paymentRepository;
        this.receiptRepository = receiptRepository;
        this.studentAccountRepository = studentAccountRepository;
        this.studentAccountService = studentAccountService;
        this.studentChargeService = studentChargeService;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    /** INITIATED to SUCCEEDED. False if the payment was no longer INITIATED (someone else confirmed it first): nothing is applied twice. */
    boolean succeed(Payment payment, UUID actor, String providerPaymentId) {
        if (paymentRepository.transitionIfStatus(payment.id(), payment.status(), PaymentStatus.SUCCEEDED) == 0) {
            return false;
        }
        if (providerPaymentId != null) {
            paymentRepository.setProviderPaymentIdIfAbsent(payment.id(), providerPaymentId);
        }
        studentAccountService.adjustBalance(payment.studentAccountId(), payment.amount().negate());
        receiptRepository.saveAndFlush(new Receipt(UUID.randomUUID(), payment.tenantId(), payment.id(), "RCT-" + payment.id(), Instant.now()));
        UUID correlationId = UUID.randomUUID();
        var event = new PaymentSuccessfulEvent(UUID.randomUUID(), Instant.now(), payment.tenantId(), payment.studentAccountId(), correlationId, null,
            payment.id(), studentIdFor(payment), payment.amount(), payment.currency());
        outboxEventWriter.write("payments", payment.studentAccountId(), event.eventType(), event.eventVersion(), event, payment.tenantId(), correlationId, null);
        auditLogWriter.write(payment.tenantId(), actor, "PAYMENT_SUCCEEDED", "finance.payments", payment.id(), Map.of("amount", payment.amount()));
        if (payment.studentChargeId() != null) {
            studentChargeService.settleFromPayment(payment.studentChargeId(), payment.id());
        }
        return true;
    }

    /** INITIATED to FAILED. */
    boolean fail(Payment payment, UUID actor, String reason) {
        if (paymentRepository.transitionIfStatus(payment.id(), payment.status(), PaymentStatus.FAILED) == 0) {
            return false;
        }
        UUID correlationId = UUID.randomUUID();
        var event = new PaymentFailedEvent(UUID.randomUUID(), Instant.now(), payment.tenantId(), payment.studentAccountId(), correlationId, null,
            payment.id(), studentIdFor(payment), reason);
        outboxEventWriter.write("payments", payment.studentAccountId(), event.eventType(), event.eventVersion(), event, payment.tenantId(), correlationId, null);
        auditLogWriter.write(payment.tenantId(), actor, "PAYMENT_FAILED", "finance.payments", payment.id(), Map.of());
        return true;
    }

    /** SUCCEEDED to REFUNDED: the balance goes back up and a charge it had settled is open again. */
    boolean refund(Payment payment, UUID actor) {
        if (paymentRepository.transitionIfStatus(payment.id(), payment.status(), PaymentStatus.REFUNDED) == 0) {
            return false;
        }
        studentAccountService.adjustBalance(payment.studentAccountId(), payment.amount());
        UUID correlationId = UUID.randomUUID();
        var event = new PaymentRefundedEvent(UUID.randomUUID(), Instant.now(), payment.tenantId(), payment.studentAccountId(), correlationId, null,
            payment.id(), studentIdFor(payment), payment.amount(), actor);
        outboxEventWriter.write("payments", payment.studentAccountId(), event.eventType(), event.eventVersion(), event, payment.tenantId(), correlationId, null);
        auditLogWriter.write(payment.tenantId(), actor, "PAYMENT_REFUNDED", "finance.payments", payment.id(), Map.of());
        if (payment.studentChargeId() != null) {
            studentChargeService.reopenAfterRefund(payment.studentChargeId(), payment.id());
        }
        return true;
    }

    private UUID studentIdFor(Payment payment) {
        return studentAccountRepository.findById(payment.studentAccountId()).map(com.setu.finance.domain.StudentAccount::studentId).orElse(null);
    }
}
