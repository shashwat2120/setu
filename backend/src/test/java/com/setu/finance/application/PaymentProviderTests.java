package com.setu.finance.application;

import com.setu.finance.domain.PaymentStatus;
import com.setu.finance.infrastructure.razorpay.RazorpaySignature;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real payment provider in front of Finance, with the provider stubbed (its wire format is tested in {@code RazorpayGatewayTests}) and everything
 * else real: PostgreSQL, the outbox, the state machine. Covers the canonical spec's payment non-negotiables: the webhook is authoritative and is
 * verified first, a provider event id is processed once, no card data is kept, and a student cannot choose what they pay.
 *
 * <p>Deliberately NOT {@code @Transactional}: the idempotency guard and the insert guards use REQUIRES_NEW (see PaymentTests).
 */
@SpringBootTest(properties = "app.finance.razorpay.webhook-secret=" + PaymentProviderTests.SECRET)
class PaymentProviderTests {

    static final String SECRET = "whsec_unit_test_secret";

    @MockitoBean
    PaymentGateway gateway;

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentProviderService providerService;
    @Autowired
    FeeStructureService feeStructureService;
    @Autowired
    FeeComponentService feeComponentService;
    @Autowired
    StudentChargeService studentChargeService;
    @Autowired
    StudentAccountService studentAccountService;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void providerOn() {
        reset(gateway);
        when(gateway.enabled()).thenReturn(true);
        when(gateway.publicKeyId()).thenReturn("rzp_test_public");
    }

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student, UUID chargeId, UUID paymentId, String orderId, String payId) {
    }

    private Day7TestFixtures.Tenant tenantWithProgram(String prefix, UUID[] programOut) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        programOut[0] = fixtures.provisionProgram(tenant, dept, prefix + "P");
        programOut[1] = fixtures.provisionCurriculum(tenant, programOut[0], "v1", java.time.LocalDate.of(2025, 1, 1), null);
        return tenant;
    }

    /** A student with one open fee of the given amount; nothing started yet. */
    private Scenario feeFor(String prefix, String amount) {
        UUID[] program = new UUID[2];
        var tenant = tenantWithProgram(prefix, program);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, program[0], program[1], prefix + "S");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        UUID structureId = ((FeeStructureService.CreateResult.Success) feeStructureService.create(tenant.tenantId(), program[0], "Tuition", null)).feeStructureId();
        UUID componentId = ((FeeComponentService.CreateResult.Success) feeComponentService.create(structureId, "TUITION", new BigDecimal(amount), "INR")).componentId();
        UUID chargeId = ((StudentChargeService.CreateResult.Success) studentChargeService.create(student.studentId(), componentId, java.time.LocalDate.now().plusDays(30))).chargeId();
        TestAuthentication.clear();
        return new Scenario(tenant, student, chargeId, null, null, null);
    }

    /** The fee, a payment started for it by the student, and the provider order the checkout opened. */
    private Scenario checkedOut(String prefix, String amount) {
        Scenario fee = feeFor(prefix, amount);
        String orderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        when(gateway.createOrder(anyLong(), anyString(), anyString(), anyMap()))
            .thenReturn(new PaymentGateway.Order(orderId, new BigDecimal(amount).movePointRight(2).longValueExact(), "INR"));
        TestAuthentication.authenticateAs(fee.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success) paymentService.initiatePayment(fee.student.studentId(), fee.chargeId, null, null, "CHECKOUT",
            "key-" + UUID.randomUUID())).paymentId();
        var checkout = (PaymentProviderService.CheckoutResult.Ready) providerService.checkout(paymentId);
        TestAuthentication.clear();
        return new Scenario(fee.tenant, fee.student, fee.chargeId, paymentId, checkout.checkout().orderId(),
            "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
    }

    private static String captured(String event, String orderId, String payId, long amountMinor, String currency) {
        return "{\"entity\":\"event\",\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId + "\",\"order_id\":\"" + orderId
            + "\",\"amount\":" + amountMinor + ",\"currency\":\"" + currency + "\",\"status\":\"captured\","
            + "\"email\":\"payer@example.com\",\"contact\":\"+919999999999\",\"card\":{\"last4\":\"1111\",\"network\":\"Visa\"},\"vpa\":\"payer@upi\"}}}}";
    }

    private static String failedAttempt(String orderId, String payId, long amountMinor) {
        return "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId + "\",\"order_id\":\"" + orderId
            + "\",\"amount\":" + amountMinor + ",\"currency\":\"INR\",\"status\":\"failed\"}}}}";
    }

    private static String refundProcessed(String payId, long amountMinor) {
        return "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\",\"payment_id\":\"" + payId + "\",\"amount\":" + amountMinor + "}}}}";
    }

    private PaymentProviderService.Outcome deliver(String body, String eventId) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return providerService.handleSignedWebhook(raw, RazorpaySignature.sign(raw, SECRET), eventId);
    }

    private String paymentStatus(UUID paymentId) {
        return jdbc.sql("select status from finance.payments where id = ?").param(paymentId).query(String.class).single();
    }

    private String chargeStatus(UUID chargeId) {
        return jdbc.sql("select status from finance.student_charges where id = ?").param(chargeId).query(String.class).single();
    }

    private BigDecimal balance(Scenario s) {
        return jdbc.sql("select balance from finance.student_accounts where tenant_id = ? and student_id = ?")
            .param(s.tenant.tenantId()).param(s.student.studentId()).query(BigDecimal.class).single();
    }

    private int events(UUID paymentId) {
        return jdbc.sql("select count(*) from finance.payment_events where payment_id = ?").param(paymentId).query(Integer.class).single();
    }

    private int receipts(UUID paymentId) {
        return jdbc.sql("select count(*) from finance.receipts where payment_id = ?").param(paymentId).query(Integer.class).single();
    }

    // ----- starting a payment for a fee -----------------------------------------------------------------------------------------------------

    @Test
    void aPaymentForAFeeTakesTheFeesAmountNotTheClients() {
        Scenario s = feeFor("PRV1", "1250.50");
        TestAuthentication.authenticateAs(s.student.userId());

        var result = paymentService.initiatePayment(s.student.studentId(), s.chargeId, null, null, "CHECKOUT", "key-" + UUID.randomUUID());

        UUID paymentId = ((PaymentService.InitiateResult.Success) result).paymentId();
        assertThat(jdbc.sql("select amount from finance.payments where id = ?").param(paymentId).query(BigDecimal.class).single()).isEqualByComparingTo("1250.50");
        assertThat(jdbc.sql("select student_charge_id from finance.payments where id = ?").param(paymentId).query(UUID.class).single()).isEqualTo(s.chargeId);
    }

    @Test
    void aClientAmountThatDisagreesWithTheFeeIsRefused() {
        Scenario s = feeFor("PRV2", "5000");
        TestAuthentication.authenticateAs(s.student.userId());

        var result = paymentService.initiatePayment(s.student.studentId(), s.chargeId, new BigDecimal("1"), "INR", "CHECKOUT", "key-" + UUID.randomUUID());

        assertThat(result).isInstanceOf(PaymentService.InitiateResult.InvalidData.class);
    }

    @Test
    void aStudentCannotPayAnotherStudentsFee() {
        Scenario mine = feeFor("PRV3", "100");
        Scenario theirs = feeFor("PRV3B", "100");
        TestAuthentication.authenticateAs(mine.student.userId());

        // their fee, my student id: the fee is not mine
        assertThat(paymentService.initiatePayment(mine.student.studentId(), theirs.chargeId, null, null, "CHECKOUT", "key-" + UUID.randomUUID()))
            .isInstanceOf(PaymentService.InitiateResult.ChargeNotFound.class);
        // their student id: the permission check refuses before anything else
        assertThatThrownBy(() -> paymentService.initiatePayment(theirs.student.studentId(), theirs.chargeId, null, null, "CHECKOUT", "key-" + UUID.randomUUID()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSecondClickOnPayReusesThePaymentInsteadOfCreatingAnother() {
        Scenario s = feeFor("PRV4", "300");
        TestAuthentication.authenticateAs(s.student.userId());

        var first = (PaymentService.InitiateResult.Success) paymentService.initiatePayment(s.student.studentId(), s.chargeId, null, null, "CHECKOUT", "key-1-" + UUID.randomUUID());
        var second = (PaymentService.InitiateResult.Success) paymentService.initiatePayment(s.student.studentId(), s.chargeId, null, null, "CHECKOUT", "key-2-" + UUID.randomUUID());

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.replayed()).isTrue();
        assertThat(jdbc.sql("select count(*) from finance.payments where student_charge_id = ?").param(s.chargeId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesTwoOpenPaymentsForOneFee() {
        Scenario s = checkedOut("PRV5", "300");

        assertThatThrownBy(() -> jdbc.sql("insert into finance.payments (id, tenant_id, student_account_id, amount, currency, status, student_charge_id) "
                + "select gen_random_uuid(), tenant_id, student_account_id, amount, currency, 'INITIATED', student_charge_id from finance.payments where id = ?")
            .param(s.paymentId).update()).hasMessageContaining("uq_payments_open_per_charge");
    }

    // ----- checkout --------------------------------------------------------------------------------------------------------------------------

    @Test
    void checkoutOpensAnOrderForExactlyTheAmountInPaise() {
        Scenario s = checkedOut("PRV6", "1250.50");

        verify(gateway).createOrder(eq(125050L), eq("INR"), eq(s.paymentId.toString()), anyMap());
        assertThat(jdbc.sql("select provider_ref from finance.payments where id = ?").param(s.paymentId).query(String.class).single()).isEqualTo(s.orderId);
    }

    @Test
    void checkoutTwiceUsesTheSameOrder() {
        Scenario s = checkedOut("PRV7", "400");
        TestAuthentication.authenticateAs(s.student.userId());

        var again = (PaymentProviderService.CheckoutResult.Ready) providerService.checkout(s.paymentId);

        assertThat(again.checkout().orderId()).isEqualTo(s.orderId);
        verify(gateway, org.mockito.Mockito.times(1)).createOrder(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void checkoutIsUnavailableWithoutAProviderAndChangesNothing() {
        Scenario fee = feeFor("PRV8", "400");
        when(gateway.enabled()).thenReturn(false);
        TestAuthentication.authenticateAs(fee.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success) paymentService.initiatePayment(fee.student.studentId(), fee.chargeId, null, null, "CHECKOUT",
            "key-" + UUID.randomUUID())).paymentId();

        var result = providerService.checkout(paymentId);

        assertThat(result).isInstanceOf(PaymentProviderService.CheckoutResult.Unavailable.class);
        verify(gateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
        assertThat(paymentStatus(paymentId)).isEqualTo("INITIATED");
    }

    @Test
    void aProviderFailureAtCheckoutIsReportedAndLeavesNoOrderBehind() {
        Scenario fee = feeFor("PRV9", "400");
        when(gateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenThrow(new PaymentGateway.ProviderException("down"));
        TestAuthentication.authenticateAs(fee.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success) paymentService.initiatePayment(fee.student.studentId(), fee.chargeId, null, null, "CHECKOUT",
            "key-" + UUID.randomUUID())).paymentId();

        var result = providerService.checkout(paymentId);

        assertThat(result).isInstanceOf(PaymentProviderService.CheckoutResult.Unavailable.class);
        assertThat(jdbc.sql("select provider_ref from finance.payments where id = ?").param(paymentId).query(String.class).optional()).isEmpty();
    }

    @Test
    void anotherStudentCannotStartCheckoutOnMyPayment() {
        Scenario mine = feeFor("PRV10", "100");
        Scenario other = feeFor("PRV10B", "100");
        TestAuthentication.authenticateAs(mine.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success) paymentService.initiatePayment(mine.student.studentId(), mine.chargeId, null, null, "CHECKOUT",
            "key-" + UUID.randomUUID())).paymentId();
        TestAuthentication.authenticateAs(other.student.userId());

        assertThatThrownBy(() -> providerService.checkout(paymentId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> providerService.reconcile(paymentId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> paymentService.get(paymentId)).isInstanceOf(AccessDeniedException.class);
        verify(gateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void anAdminOfAnotherCollegeCannotSeeOrCheckOutThisPayment() {
        Scenario mine = checkedOut("PRV11", "100");
        Scenario other = feeFor("PRV11B", "100");
        TestAuthentication.authenticateAs(other.tenant.adminUserId());

        assertThatThrownBy(() -> paymentService.get(mine.paymentId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> providerService.reconcile(mine.paymentId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> paymentService.refund(mine.paymentId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theOwnerAndTheirCollegesAdminCanReadThePaymentsStatus() {
        Scenario s = checkedOut("PRV12", "100");

        TestAuthentication.authenticateAs(s.student.userId());
        assertThat(paymentService.get(s.paymentId)).get().extracting(p -> p.status()).isEqualTo(PaymentStatus.INITIATED);
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        assertThat(paymentService.get(s.paymentId)).isPresent();
    }

    // ----- the signed webhook ---------------------------------------------------------------------------------------------------------------

    @Test
    void aSignedCaptureSucceedsThePaymentSettlesTheFeeAdjustsTheBalanceAndIssuesAReceiptWithNoUserLoggedIn() {
        Scenario s = checkedOut("PRV13", "2000");
        assertThat(balance(s)).isEqualByComparingTo("2000");

        var outcome = deliver(captured("payment.captured", s.orderId, s.payId, 200000, "INR"), "evt-" + UUID.randomUUID());

        assertThat(outcome).isEqualTo(new PaymentProviderService.Outcome.Applied(PaymentStatus.SUCCEEDED));
        assertThat(paymentStatus(s.paymentId)).isEqualTo("SUCCEEDED");
        assertThat(chargeStatus(s.chargeId)).isEqualTo("PAID");
        assertThat(balance(s)).isEqualByComparingTo("0");
        assertThat(receipts(s.paymentId)).isEqualTo(1);
        assertThat(jdbc.sql("select provider_payment_id from finance.payments where id = ?").param(s.paymentId).query(String.class).single()).isEqualTo(s.payId);
    }

    @Test
    void anUnsignedOrBadlySignedWebhookChangesNothing() {
        Scenario s = checkedOut("PRV14", "2000");
        byte[] raw = captured("payment.captured", s.orderId, s.payId, 200000, "INR").getBytes(StandardCharsets.UTF_8);

        assertThat(providerService.handleSignedWebhook(raw, null, "evt-1")).isInstanceOf(PaymentProviderService.Outcome.BadSignature.class);
        assertThat(providerService.handleSignedWebhook(raw, "deadbeef", "evt-2")).isInstanceOf(PaymentProviderService.Outcome.BadSignature.class);
        assertThat(providerService.handleSignedWebhook(raw, RazorpaySignature.sign(raw, "someone-elses-secret"), "evt-3"))
            .isInstanceOf(PaymentProviderService.Outcome.BadSignature.class);

        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
        assertThat(chargeStatus(s.chargeId)).isEqualTo("PENDING");
        assertThat(events(s.paymentId)).isZero();
    }

    @Test
    void aBodyAlteredAfterSigningIsRefused() {
        Scenario s = checkedOut("PRV15", "2000");
        byte[] honest = captured("payment.captured", s.orderId, s.payId, 1, "INR").getBytes(StandardCharsets.UTF_8);
        String signature = RazorpaySignature.sign(honest, SECRET);
        // an attacker keeps the signature of a cheap payment and swaps in the full amount
        byte[] altered = captured("payment.captured", s.orderId, s.payId, 200000, "INR").getBytes(StandardCharsets.UTF_8);

        assertThat(providerService.handleSignedWebhook(altered, signature, "evt-x")).isInstanceOf(PaymentProviderService.Outcome.BadSignature.class);
        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
    }

    @Test
    void theSameEventDeliveredTwiceIsAppliedOnce() {
        Scenario s = checkedOut("PRV16", "2000");
        String body = captured("payment.captured", s.orderId, s.payId, 200000, "INR");
        String eventId = "evt-" + UUID.randomUUID();

        var first = deliver(body, eventId);
        var second = deliver(body, eventId);

        assertThat(first).isInstanceOf(PaymentProviderService.Outcome.Applied.class);
        assertThat(second).isInstanceOf(PaymentProviderService.Outcome.Duplicate.class);
        assertThat(balance(s)).isEqualByComparingTo("0");
        assertThat(receipts(s.paymentId)).isEqualTo(1);
    }

    @Test
    void twoDifferentEventsForTheSamePaymentSettleItOnce() {
        Scenario s = checkedOut("PRV17", "2000");

        // Razorpay sends both payment.captured and order.paid for one successful payment, each with its own event id
        var a = deliver(captured("payment.captured", s.orderId, s.payId, 200000, "INR"), "evt-a-" + UUID.randomUUID());
        var b = deliver(captured("order.paid", s.orderId, s.payId, 200000, "INR"), "evt-b-" + UUID.randomUUID());

        assertThat(a).isInstanceOf(PaymentProviderService.Outcome.Applied.class);
        assertThat(b).isInstanceOf(PaymentProviderService.Outcome.Ignored.class);
        assertThat(balance(s)).isEqualByComparingTo("0");
        assertThat(receipts(s.paymentId)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from governance.audit_logs where action = 'PAYMENT_SUCCEEDED' and resource_id = ?").param(s.paymentId)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventApplyOnce() throws Exception {
        Scenario s = checkedOut("PRV18", "2000");
        String body = captured("payment.captured", s.orderId, s.payId, 200000, "INR");
        String eventId = "evt-" + UUID.randomUUID();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    try {
                        return deliver(body, eventId);
                    } catch (org.springframework.dao.DataIntegrityViolationException raced) {
                        return raced;
                    }
                }));
            }
            for (var r : results) {
                r.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(paymentStatus(s.paymentId)).isEqualTo("SUCCEEDED");
        assertThat(balance(s)).isEqualByComparingTo("0");
        assertThat(receipts(s.paymentId)).isEqualTo(1);
        assertThat(events(s.paymentId)).isEqualTo(1);
    }

    @Test
    void anAmountThatIsNotThePaymentsIsNotAppliedAndIsRaisedInTheAuditLog() {
        Scenario s = checkedOut("PRV19", "2000");

        var outcome = deliver(captured("payment.captured", s.orderId, s.payId, 100, "INR"), "evt-" + UUID.randomUUID());

        assertThat(outcome).isInstanceOf(PaymentProviderService.Outcome.Ignored.class);
        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
        assertThat(chargeStatus(s.chargeId)).isEqualTo("PENDING");
        assertThat(balance(s)).isEqualByComparingTo("2000");
        assertThat(jdbc.sql("select count(*) from governance.audit_logs where action = 'PAYMENT_PROVIDER_MISMATCH' and resource_id = ?").param(s.paymentId)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aCurrencyThatIsNotThePaymentsIsNotApplied() {
        Scenario s = checkedOut("PRV20", "2000");

        var outcome = deliver(captured("payment.captured", s.orderId, s.payId, 200000, "USD"), "evt-" + UUID.randomUUID());

        assertThat(outcome).isInstanceOf(PaymentProviderService.Outcome.Ignored.class);
        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
    }

    @Test
    void anOrderThatIsNotOursIsAcknowledgedAndIgnored() {
        checkedOut("PRV21", "2000");

        var outcome = deliver(captured("payment.captured", "order_someone_else", "pay_ZZZ", 200000, "INR"), "evt-" + UUID.randomUUID());

        assertThat(outcome).isInstanceOf(PaymentProviderService.Outcome.Ignored.class);
    }

    @Test
    void eventsThisSystemDoesNotUseAreAcknowledgedAndIgnored() {
        var outcome = deliver("{\"event\":\"settlement.processed\",\"payload\":{}}", "evt-" + UUID.randomUUID());

        assertThat(outcome).isInstanceOf(PaymentProviderService.Outcome.Ignored.class);
    }

    @Test
    void aFailedAttemptDoesNotFailThePaymentBecauseTheOrderCanStillBePaid() {
        Scenario s = checkedOut("PRV22", "2000");

        var failed = deliver(failedAttempt(s.orderId, s.payId + "a", 200000), "evt-f-" + UUID.randomUUID());
        assertThat(failed).isInstanceOf(PaymentProviderService.Outcome.Ignored.class);
        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
        assertThat(events(s.paymentId)).isEqualTo(1);

        // the student tries again in the same checkout and it works: the money must land
        var paid = deliver(captured("payment.captured", s.orderId, s.payId + "b", 200000, "INR"), "evt-p-" + UUID.randomUUID());
        assertThat(paid).isInstanceOf(PaymentProviderService.Outcome.Applied.class);
        assertThat(paymentStatus(s.paymentId)).isEqualTo("SUCCEEDED");
        assertThat(balance(s)).isEqualByComparingTo("0");
    }

    @Test
    void noCardDetailsOrContactDataAreKept() {
        Scenario s = checkedOut("PRV23", "2000");
        deliver(captured("payment.captured", s.orderId, s.payId, 200000, "INR"), "evt-" + UUID.randomUUID());

        String stored = jdbc.sql("select payload_json::text from finance.payment_events where payment_id = ?").param(s.paymentId).query(String.class).single();

        assertThat(stored).contains(s.payId).contains("200000").contains("INR");
        assertThat(stored).doesNotContain("1111").doesNotContain("Visa").doesNotContain("payer@example.com").doesNotContain("9999999999").doesNotContain("payer@upi");
    }

    @Test
    void nothingIsAcceptedWhenNoWebhookSecretIsConfigured() {
        var unconfigured = new PaymentProviderService(null, null, null, null, gateway, null, new com.fasterxml.jackson.databind.ObjectMapper(), "");
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(unconfigured.handleSignedWebhook(raw, "not-a-real-signature", "evt-1")).isInstanceOf(PaymentProviderService.Outcome.NotConfigured.class);
    }

    @Test
    void aWebhookWithoutAnEventIdIsRefusedBeforeItIsRead() {
        byte[] raw = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(providerService.handleSignedWebhook(raw, RazorpaySignature.sign(raw, SECRET), null)).isInstanceOf(PaymentProviderService.Outcome.Malformed.class);
    }

    // ----- lookup (reconcile) ---------------------------------------------------------------------------------------------------------------

    @Test
    void reconcileAppliesWhatTheProviderSaysIsCapturedAndASecondCallChangesNothing() {
        Scenario s = checkedOut("PRV24", "2000");
        when(gateway.paymentsForOrder(s.orderId)).thenReturn(List.of(
            new PaymentGateway.ProviderPayment(s.payId + "a", s.orderId, "failed", 200000, "INR"),
            new PaymentGateway.ProviderPayment(s.payId + "b", s.orderId, "captured", 200000, "INR")));
        TestAuthentication.authenticateAs(s.student.userId());

        var first = providerService.reconcile(s.paymentId);
        var second = providerService.reconcile(s.paymentId);

        assertThat(first).isEqualTo(new PaymentProviderService.ReconcileResult.Status(PaymentStatus.SUCCEEDED, true));
        assertThat(second).isEqualTo(new PaymentProviderService.ReconcileResult.Status(PaymentStatus.SUCCEEDED, false));
        assertThat(chargeStatus(s.chargeId)).isEqualTo("PAID");
        assertThat(balance(s)).isEqualByComparingTo("0");
    }

    @Test
    void reconcileDoesNotBelieveAPaymentTheProviderHasNotCaptured() {
        Scenario s = checkedOut("PRV25", "2000");
        when(gateway.paymentsForOrder(s.orderId)).thenReturn(List.of(new PaymentGateway.ProviderPayment(s.payId, s.orderId, "authorized", 200000, "INR")));
        TestAuthentication.authenticateAs(s.student.userId());

        var result = providerService.reconcile(s.paymentId);

        assertThat(result).isEqualTo(new PaymentProviderService.ReconcileResult.Status(PaymentStatus.INITIATED, false));
        assertThat(chargeStatus(s.chargeId)).isEqualTo("PENDING");
    }

    @Test
    void reconcileRefusesACaptureOfTheWrongAmount() {
        Scenario s = checkedOut("PRV26", "2000");
        when(gateway.paymentsForOrder(s.orderId)).thenReturn(List.of(new PaymentGateway.ProviderPayment(s.payId, s.orderId, "captured", 5, "INR")));
        TestAuthentication.authenticateAs(s.student.userId());

        var result = providerService.reconcile(s.paymentId);

        assertThat(result).isEqualTo(new PaymentProviderService.ReconcileResult.Status(PaymentStatus.INITIATED, false));
        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
    }

    @Test
    void reconcileAfterTheWebhookAlreadyArrivedChangesNothingAndAsksNoOne() {
        Scenario s = checkedOut("PRV27", "2000");
        deliver(captured("payment.captured", s.orderId, s.payId, 200000, "INR"), "evt-" + UUID.randomUUID());
        TestAuthentication.authenticateAs(s.student.userId());

        var result = providerService.reconcile(s.paymentId);

        assertThat(result).isEqualTo(new PaymentProviderService.ReconcileResult.Status(PaymentStatus.SUCCEEDED, false));
        verify(gateway, never()).paymentsForOrder(anyString());
    }

    @Test
    void reconcileReportsAProviderOutageInsteadOfGuessing() {
        Scenario s = checkedOut("PRV28", "2000");
        when(gateway.paymentsForOrder(s.orderId)).thenThrow(new PaymentGateway.ProviderException("unreachable"));
        TestAuthentication.authenticateAs(s.student.userId());

        assertThat(providerService.reconcile(s.paymentId)).isInstanceOf(PaymentProviderService.ReconcileResult.Unavailable.class);
        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
    }

    @Test
    void reconcileBeforeCheckoutHasNoOrderToAskAbout() {
        Scenario fee = feeFor("PRV29", "100");
        TestAuthentication.authenticateAs(fee.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success) paymentService.initiatePayment(fee.student.studentId(), fee.chargeId, null, null, "CHECKOUT",
            "key-" + UUID.randomUUID())).paymentId();

        assertThat(providerService.reconcile(paymentId)).isInstanceOf(PaymentProviderService.ReconcileResult.NoProviderOrder.class);
    }

    // ----- refunds ----------------------------------------------------------------------------------------------------------------------------

    private Scenario paid(String prefix, String amount) {
        Scenario s = checkedOut(prefix, amount);
        deliver(captured("payment.captured", s.orderId, s.payId, new BigDecimal(amount).movePointRight(2).longValueExact(), "INR"), "evt-" + UUID.randomUUID());
        return s;
    }

    @Test
    void anAdminRefundGoesThroughTheProviderFirstThenReopensTheFeeAndRestoresTheBalance() {
        Scenario s = paid("PRV30", "2000");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var result = paymentService.refund(s.paymentId);

        assertThat(result).isInstanceOf(PaymentService.RefundResult.Success.class);
        verify(gateway).refundInFull(eq(s.payId), eq(200000L), eq("RF-" + s.paymentId));
        assertThat(paymentStatus(s.paymentId)).isEqualTo("REFUNDED");
        assertThat(chargeStatus(s.chargeId)).isEqualTo("PENDING");
        assertThat(balance(s)).isEqualByComparingTo("2000");
    }

    @Test
    void ifTheProviderWillNotRefundNothingChangesHere() {
        Scenario s = paid("PRV31", "2000");
        org.mockito.Mockito.doThrow(new PaymentGateway.ProviderException("insufficient balance")).when(gateway).refundInFull(anyString(), anyLong(), anyString());
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var result = paymentService.refund(s.paymentId);

        assertThat(result).isInstanceOf(PaymentService.RefundResult.ProviderFailed.class);
        assertThat(paymentStatus(s.paymentId)).isEqualTo("SUCCEEDED");
        assertThat(chargeStatus(s.chargeId)).isEqualTo("PAID");
        assertThat(balance(s)).isEqualByComparingTo("0");
    }

    @Test
    void aRefundTheProviderReportsFromItsOwnDashboardIsApplied() {
        Scenario s = paid("PRV32", "2000");

        var outcome = deliver(refundProcessed(s.payId, 200000), "evt-" + UUID.randomUUID());

        assertThat(outcome).isEqualTo(new PaymentProviderService.Outcome.Applied(PaymentStatus.REFUNDED));
        assertThat(paymentStatus(s.paymentId)).isEqualTo("REFUNDED");
        assertThat(chargeStatus(s.chargeId)).isEqualTo("PENDING");
        assertThat(balance(s)).isEqualByComparingTo("2000");
    }

    @Test
    void aPartialRefundIsNotAppliedAndIsRaisedInTheAuditLog() {
        Scenario s = paid("PRV33", "2000");

        var outcome = deliver(refundProcessed(s.payId, 50000), "evt-" + UUID.randomUUID());

        assertThat(outcome).isInstanceOf(PaymentProviderService.Outcome.Ignored.class);
        assertThat(paymentStatus(s.paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("select count(*) from governance.audit_logs where action = 'PAYMENT_PARTIAL_REFUND_NOT_APPLIED' and resource_id = ?").param(s.paymentId)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aStudentCannotRefundTheirOwnPayment() {
        Scenario s = paid("PRV34", "2000");
        TestAuthentication.authenticateAs(s.student.userId());

        assertThatThrownBy(() -> paymentService.refund(s.paymentId)).isInstanceOf(AccessDeniedException.class);
        verify(gateway, never()).refundInFull(anyString(), anyLong(), anyString());
    }

    @Test
    void aPaymentRecordedByTheAdminStandInHasNoProviderPaymentSoRefundNeverCallsTheProvider() {
        Scenario fee = feeFor("PRV35", "500");
        TestAuthentication.authenticateAs(fee.student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success) paymentService.initiatePayment(fee.student.studentId(), fee.chargeId, null, null, "CASH",
            "key-" + UUID.randomUUID())).paymentId();
        TestAuthentication.authenticateAs(fee.tenant.adminUserId());
        paymentService.recordWebhookEvent(paymentId, "evt-" + UUID.randomUUID(), "PAYMENT_SUCCEEDED", null);
        assertThat(chargeStatus(fee.chargeId)).isEqualTo("PAID");

        var result = paymentService.refund(paymentId);

        assertThat(result).isInstanceOf(PaymentService.RefundResult.Success.class);
        verify(gateway, never()).refundInFull(anyString(), anyLong(), anyString());
        assertThat(chargeStatus(fee.chargeId)).isEqualTo("PENDING");
    }

    @Test
    void aFeeThatIsAlreadyPaidCannotBePaidAgain() {
        Scenario s = paid("PRV36", "2000");
        TestAuthentication.authenticateAs(s.student.userId());

        var result = paymentService.initiatePayment(s.student.studentId(), s.chargeId, null, null, "CHECKOUT", "key-" + UUID.randomUUID());

        assertThat(result).isInstanceOf(PaymentService.InitiateResult.ChargeNotPayable.class);
    }

    @Test
    void currenciesWithoutTwoDecimalsAreRefusedAtCheckoutNotMisCharged() {
        assertThatThrownBy(() -> PaymentProviderService.minorUnits(new BigDecimal("100"), "JPY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentProviderService.minorUnits(new BigDecimal("1.005"), "INR")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PaymentProviderService.minorUnits(new BigDecimal("1250.50"), "INR")).isEqualTo(125050L);
        assertThat(PaymentProviderService.minorUnits(new BigDecimal("0.01"), "USD")).isEqualTo(1L);
    }
}
