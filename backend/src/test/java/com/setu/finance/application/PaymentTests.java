package com.setu.finance.application;

import com.setu.finance.domain.PaymentStatus;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 12 §Finance: payments/payment_events/receipts — initiation
 * idempotency (replay, conflict), webhook-driven state-machines.md #20
 * transitions, refund, authorization. Deliberately NOT {@code
 * @Transactional} — {@code IdempotencyGuard}/payment writes touch
 * REQUIRES_NEW paths (see FinanceCoreTests' class javadoc for the same
 * reasoning).
 */
@SpringBootTest
class PaymentTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    PaymentService paymentService;
    @Autowired
    ReceiptService receiptService;
    @Autowired
    StudentAccountService studentAccountService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        return new Scenario(tenant, student);
    }

    @Test
    void studentCanInitiateOwnPayment() {
        var s = setUp("PAY1");
        TestAuthentication.authenticateAs(s.student.userId());

        var result = paymentService.initiatePayment(s.student.studentId(), new BigDecimal("1000"), "INR", "UPI", "key-" + UUID.randomUUID());

        assertThat(result).isInstanceOf(PaymentService.InitiateResult.Success.class);
        assertThat(((PaymentService.InitiateResult.Success) result).replayed()).isFalse();
    }

    @Test
    void studentCannotInitiatePaymentForAnotherStudent() {
        var s = setUp("PAY2");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant,
            fixtures.provisionProgram(s.tenant, fixtures.provisionDepartment(s.tenant, "PAY2D2"), "PAY2P2"),
            fixtures.provisionCurriculum(s.tenant, fixtures.provisionProgram(s.tenant, fixtures.provisionDepartment(s.tenant, "PAY2D3"), "PAY2P3"), "v1", java.time.LocalDate.of(2025, 1, 1), null),
            "PAY2O");
        TestAuthentication.authenticateAs(s.student.userId());

        assertThatThrownBy(() -> paymentService.initiatePayment(other.studentId(), new BigDecimal("100"), "INR", "UPI", "key-" + UUID.randomUUID()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void repeatedRequestWithSameIdempotencyKeyAndSamePayloadReplaysResult() {
        var s = setUp("PAY3");
        TestAuthentication.authenticateAs(s.student.userId());
        String key = "key-" + UUID.randomUUID();

        var first = (PaymentService.InitiateResult.Success)
            paymentService.initiatePayment(s.student.studentId(), new BigDecimal("500"), "INR", "UPI", key);
        var second = (PaymentService.InitiateResult.Success)
            paymentService.initiatePayment(s.student.studentId(), new BigDecimal("500"), "INR", "UPI", key);

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.replayed()).isTrue();
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsConflict() {
        var s = setUp("PAY4");
        TestAuthentication.authenticateAs(s.student.userId());
        String key = "key-" + UUID.randomUUID();
        paymentService.initiatePayment(s.student.studentId(), new BigDecimal("500"), "INR", "UPI", key);

        var second = paymentService.initiatePayment(s.student.studentId(), new BigDecimal("999"), "INR", "UPI", key);

        assertThat(second).isInstanceOf(PaymentService.InitiateResult.IdempotencyKeyConflict.class);
    }

    @Test
    void webhookSuccessTransitionsPaymentAdjustsBalanceAndIssuesReceipt() {
        var s = setUp("PAY5");
        TestAuthentication.authenticateAs(s.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success)
            paymentService.initiatePayment(s.student.studentId(), new BigDecimal("2000"), "INR", "UPI", "key-" + UUID.randomUUID())).paymentId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        var webhookResult = paymentService.recordWebhookEvent(paymentId, "evt-" + UUID.randomUUID(), "PAYMENT_SUCCEEDED", null);

        assertThat(webhookResult).isInstanceOf(PaymentService.WebhookResult.Success.class);
        assertThat(((PaymentService.WebhookResult.Success) webhookResult).newStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        var account = studentAccountService.get(s.tenant.tenantId(), s.student.studentId()).orElseThrow();
        assertThat(account.balance()).isEqualByComparingTo("-2000");
        assertThat(receiptService.getForPayment(paymentId)).isPresent();
    }

    @Test
    void duplicateWebhookEventIsNoOp() {
        var s = setUp("PAY6");
        TestAuthentication.authenticateAs(s.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success)
            paymentService.initiatePayment(s.student.studentId(), new BigDecimal("100"), "INR", "UPI", "key-" + UUID.randomUUID())).paymentId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        String eventId = "evt-" + UUID.randomUUID();
        var first = paymentService.recordWebhookEvent(paymentId, eventId, "PAYMENT_SUCCEEDED", null);
        var second = paymentService.recordWebhookEvent(paymentId, eventId, "PAYMENT_SUCCEEDED", null);

        assertThat(first).isInstanceOf(PaymentService.WebhookResult.Success.class);
        assertThat(second).isInstanceOf(PaymentService.WebhookResult.AlreadyProcessed.class);
    }

    @Test
    void refundReversesBalanceAndIsTerminal() {
        var s = setUp("PAY7");
        TestAuthentication.authenticateAs(s.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success)
            paymentService.initiatePayment(s.student.studentId(), new BigDecimal("300"), "INR", "UPI", "key-" + UUID.randomUUID())).paymentId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        paymentService.recordWebhookEvent(paymentId, "evt-" + UUID.randomUUID(), "PAYMENT_SUCCEEDED", null);

        var refundResult = paymentService.refund(paymentId);
        assertThat(refundResult).isInstanceOf(PaymentService.RefundResult.Success.class);
        var account = studentAccountService.get(s.tenant.tenantId(), s.student.studentId()).orElseThrow();
        assertThat(account.balance()).isEqualByComparingTo("0");

        var secondRefund = paymentService.refund(paymentId);
        assertThat(secondRefund).isInstanceOf(PaymentService.RefundResult.InvalidTransition.class);
    }

    @Test
    void facultyCannotRecordWebhookEventOrRefund() {
        var s = setUp("PAY8");
        UUID facultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "PAY8-F");
        TestAuthentication.authenticateAs(s.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success)
            paymentService.initiatePayment(s.student.studentId(), new BigDecimal("100"), "INR", "UPI", "key-" + UUID.randomUUID())).paymentId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(facultyUserId);
        assertThatThrownBy(() -> paymentService.recordWebhookEvent(paymentId, "evt-x", "PAYMENT_SUCCEEDED", null))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> paymentService.refund(paymentId)).isInstanceOf(AccessDeniedException.class);
    }
}
