package com.setu.finance.infrastructure.web;

import com.setu.finance.application.PaymentService;
import com.setu.finance.infrastructure.razorpay.RazorpaySignature;
import com.setu.identity.TestAuthentication;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The provider's webhook over real HTTP and the real Spring Security chain: it must be reachable with NO token (the provider has none), and for that
 * reason it must be the signature alone that decides. Also the student-facing endpoints around it: status, checkout without a provider, and who may
 * ask. No provider keys are configured in tests, so checkout answers 503 here; the provider's behaviour is covered in PaymentProviderTests.
 */
@SpringBootTest(properties = "app.finance.razorpay.webhook-secret=" + ProviderWebhookHttpTests.SECRET)
@AutoConfigureMockMvc
class ProviderWebhookHttpTests {

    static final String SECRET = "whsec_http_test_secret";
    private static final String URL = "/finance/provider-webhooks/razorpay";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    PaymentService paymentService;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student, UUID paymentId, String orderId, String payId) {
    }

    /** A student with one INITIATED payment of 100.00 INR that has a provider order, as after a checkout. */
    private Scenario withOrder(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        TestAuthentication.authenticateAs(student.userId());
        UUID paymentId = ((PaymentService.InitiateResult.Success) paymentService.initiatePayment(student.studentId(), new BigDecimal("100.00"), "INR",
            "CHECKOUT", "key-" + UUID.randomUUID())).paymentId();
        // spring-security-test keeps its own copy of the thread's authentication for MockMvc; clearing only SecurityContextHolder would let the
        // fixture student leak into the first requests below, which are meant to carry no token at all.
        TestAuthentication.clear();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        String orderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        jdbc.sql("update finance.payments set provider_ref = ? where id = ?").param(orderId).param(paymentId).update();
        return new Scenario(tenant, student, paymentId, orderId, "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
    }

    private String capture(Scenario s, long amountMinor) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + s.payId + "\",\"order_id\":\"" + s.orderId
            + "\",\"amount\":" + amountMinor + ",\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    }

    private String paymentStatus(UUID paymentId) {
        return jdbc.sql("select status from finance.payments where id = ?").param(paymentId).query(String.class).single();
    }

    private String bearerFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId);
    }

    @Test
    void aSignedWebhookIsAcceptedWithoutAnyUserToken() throws Exception {
        Scenario s = withOrder("PWH1");
        String body = capture(s, 10000);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", RazorpaySignature.sign(body.getBytes(StandardCharsets.UTF_8), SECRET))
                .header("X-Razorpay-Event-Id", "evt-" + UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("APPLIED"));

        assertThat(paymentStatus(s.paymentId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void theSameDeliveryRetriedIsAcknowledgedAsADuplicateAndNotAppliedTwice() throws Exception {
        Scenario s = withOrder("PWH2");
        String body = capture(s, 10000);
        String signature = RazorpaySignature.sign(body.getBytes(StandardCharsets.UTF_8), SECRET);
        String eventId = "evt-" + UUID.randomUUID();

        for (String expected : new String[]{"APPLIED", "DUPLICATE"}) {
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                    .header("X-Razorpay-Signature", signature).header("X-Razorpay-Event-Id", eventId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(expected));
        }

        assertThat(jdbc.sql("select count(*) from finance.receipts where payment_id = ?").param(s.paymentId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aWrongSignatureIsRefusedByTheEndpointItselfNotJustByTheFilterChain() throws Exception {
        Scenario s = withOrder("PWH3");
        String body = capture(s, 10000);

        // The body of the refusal is the webhook's own ("REFUSED"), proving the request reached the endpoint without a token and was turned away by the signature check.
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", "0".repeat(64)).header("X-Razorpay-Event-Id", "evt-" + UUID.randomUUID()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.result").value("REFUSED"));

        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
    }

    @Test
    void aMissingSignatureHeaderIsRefused() throws Exception {
        Scenario s = withOrder("PWH4");

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(capture(s, 10000)).header("X-Razorpay-Event-Id", "evt-1"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.result").value("REFUSED"));

        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
    }

    @Test
    void aValidSignatureForTheWrongAmountIsAcknowledgedButNotApplied() throws Exception {
        Scenario s = withOrder("PWH5");
        String body = capture(s, 1);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", RazorpaySignature.sign(body.getBytes(StandardCharsets.UTF_8), SECRET))
                .header("X-Razorpay-Event-Id", "evt-" + UUID.randomUUID()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IGNORED"));

        assertThat(paymentStatus(s.paymentId)).isEqualTo("INITIATED");
    }

    @Test
    void onlyPostIsOpenWithoutAToken() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void otherFinanceEndpointsStillNeedAToken() throws Exception {
        Scenario s = withOrder("PWH6");

        mockMvc.perform(post("/finance/payments/" + s.paymentId + "/checkout")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/finance/payments/" + s.paymentId + "/reconcile")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/finance/payments/" + s.paymentId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/finance/payments/" + s.paymentId + "/refund")).andExpect(status().isUnauthorized());
        // the provider path itself is open (and verified by signature); nothing else exists under it
        mockMvc.perform(post("/finance/provider-webhooks/other")).andExpect(status().isNotFound());
    }

    @Test
    void checkoutWithoutProviderKeysIsAnHonest503() throws Exception {
        Scenario s = withOrder("PWH7");
        jdbc.sql("update finance.payments set provider_ref = null where id = ?").param(s.paymentId).update();

        mockMvc.perform(post("/finance/payments/" + s.paymentId + "/checkout").header("Authorization", bearerFor(s.student.userId())))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("online payment is not enabled for this college"));
    }

    @Test
    void theOwnerReadsTheStatusAndAnotherStudentIsRefused() throws Exception {
        Scenario mine = withOrder("PWH8");
        Scenario other = withOrder("PWH8B");

        mockMvc.perform(get("/finance/payments/" + mine.paymentId).header("Authorization", bearerFor(mine.student.userId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INITIATED"))
            .andExpect(jsonPath("$.checkoutStarted").value(true));
        mockMvc.perform(get("/finance/payments/" + mine.paymentId).header("Authorization", bearerFor(other.student.userId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void aPaymentThatDoesNotExistIs404() throws Exception {
        Scenario s = withOrder("PWH9");

        mockMvc.perform(get("/finance/payments/" + UUID.randomUUID()).header("Authorization", bearerFor(s.student.userId())))
            .andExpect(status().isNotFound());
    }

    @Test
    void theCheckoutAnswerNeverContainsASecretOrCardField() throws Exception {
        // With the provider off the answer is a plain error; assert the shape so a future change cannot add a secret to it unnoticed.
        Scenario s = withOrder("PWH10");
        jdbc.sql("update finance.payments set provider_ref = null where id = ?").param(s.paymentId).update();

        String answer = mockMvc.perform(post("/finance/payments/" + s.paymentId + "/checkout").header("Authorization", bearerFor(s.student.userId())))
            .andReturn().getResponse().getContentAsString();

        assertThat(answer.toLowerCase()).doesNotContain("secret").doesNotContain("cvv").doesNotContain("card");
    }
}
