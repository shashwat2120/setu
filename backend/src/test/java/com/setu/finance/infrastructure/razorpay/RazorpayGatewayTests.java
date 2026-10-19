package com.setu.finance.infrastructure.razorpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.finance.application.PaymentGateway;
import com.setu.testsupport.BlockingStubServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Razorpay client against a stub that speaks its wire format (a real blocking socket, like the other HTTP-client tests here). What is checked is
 * what Razorpay would see: the path, the Basic credentials, the body in paise, and that every way the provider can fail comes out as a
 * {@link PaymentGateway.ProviderException} that does not contain the secret.
 */
class RazorpayGatewayTests {

    private static final String KEY_ID = "rzp_test_abc123";
    private static final String SECRET = "super-secret-value";
    private final ObjectMapper mapper = new ObjectMapper();

    private RazorpayGateway gateway(BlockingStubServer stub, String keyId, String secret) {
        return new RazorpayGateway(keyId, secret, stub.uri().toString() + "/v1", Duration.ofSeconds(3), mapper);
    }

    @Test
    void withoutKeysItIsDisabledAndNothingIsSent() throws Exception {
        try (var stub = new BlockingStubServer()) {
            var gateway = gateway(stub, "", "");

            assertThat(gateway.enabled()).isFalse();
            assertThatThrownBy(gateway::publicKeyId).isInstanceOf(PaymentGateway.NotConfiguredException.class);
            assertThatThrownBy(() -> gateway.createOrder(100, "INR", "r", Map.of())).isInstanceOf(PaymentGateway.NotConfiguredException.class);
            assertThat(stub.seen).isEmpty();
        }
    }

    @Test
    void aKeyIdWithoutASecretIsStillDisabled() throws Exception {
        try (var stub = new BlockingStubServer()) {
            assertThat(gateway(stub, KEY_ID, "").enabled()).isFalse();
        }
    }

    @Test
    void createOrderSendsPaiseWithBasicCredentialsAndReadsTheOrder() throws Exception {
        try (var stub = new BlockingStubServer()) {
            stub.response = BlockingStubServer.json(200, "{\"id\":\"order_XyZ1\",\"amount\":125050,\"currency\":\"INR\",\"status\":\"created\"}");

            var order = gateway(stub, KEY_ID, SECRET).createOrder(125050, "INR", "pay-1", Map.of("paymentId", "pay-1"));

            assertThat(order.id()).isEqualTo("order_XyZ1");
            assertThat(order.amountMinor()).isEqualTo(125050);
            var seen = stub.seen.get(0);
            assertThat(seen.requestLine()).startsWith("POST /v1/orders ");
            assertThat(seen.header("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + SECRET).getBytes()));
            var body = mapper.readTree(seen.body());
            assertThat(body.get("amount").asLong()).isEqualTo(125050);
            assertThat(body.get("currency").asText()).isEqualTo("INR");
            assertThat(body.get("receipt").asText()).isEqualTo("pay-1");
        }
    }

    @Test
    void publicKeyIsTheIdNeverTheSecret() throws Exception {
        try (var stub = new BlockingStubServer()) {
            assertThat(gateway(stub, KEY_ID, SECRET).publicKeyId()).isEqualTo(KEY_ID).doesNotContain(SECRET);
        }
    }

    @Test
    void paymentsForOrderReadsEveryAttempt() throws Exception {
        try (var stub = new BlockingStubServer()) {
            stub.response = BlockingStubServer.json(200, "{\"entity\":\"collection\",\"count\":2,\"items\":["
                + "{\"id\":\"pay_A\",\"order_id\":\"order_1\",\"status\":\"failed\",\"amount\":500,\"currency\":\"INR\"},"
                + "{\"id\":\"pay_B\",\"order_id\":\"order_1\",\"status\":\"captured\",\"amount\":500,\"currency\":\"INR\"}]}");

            var payments = gateway(stub, KEY_ID, SECRET).paymentsForOrder("order_1");

            assertThat(payments).extracting(PaymentGateway.ProviderPayment::id).containsExactly("pay_A", "pay_B");
            assertThat(payments.get(0).captured()).isFalse();
            assertThat(payments.get(1).captured()).isTrue();
            assertThat(stub.seen.get(0).requestLine()).startsWith("GET /v1/orders/order_1/payments ");
        }
    }

    @Test
    void refundIsSentOnceForTheFullAmount() throws Exception {
        try (var stub = new BlockingStubServer()) {
            stub.responder = seen -> seen.requestLine().startsWith("GET")
                ? BlockingStubServer.json(200, "{\"id\":\"pay_A\",\"order_id\":\"order_1\",\"status\":\"captured\",\"amount\":500,\"currency\":\"INR\"}")
                : BlockingStubServer.json(200, "{\"id\":\"rfnd_1\",\"payment_id\":\"pay_A\",\"amount\":500}");

            gateway(stub, KEY_ID, SECRET).refundInFull("pay_A", 500, "RF-1");

            assertThat(stub.seen).hasSize(2);
            assertThat(stub.seen.get(1).requestLine()).startsWith("POST /v1/payments/pay_A/refund ");
            assertThat(mapper.readTree(stub.seen.get(1).body()).get("amount").asLong()).isEqualTo(500);
        }
    }

    @Test
    void aPaymentTheProviderAlreadyRefundedIsNotRefundedAgain() throws Exception {
        try (var stub = new BlockingStubServer()) {
            stub.response = BlockingStubServer.json(200, "{\"id\":\"pay_A\",\"order_id\":\"order_1\",\"status\":\"refunded\",\"amount\":500,\"currency\":\"INR\"}");

            gateway(stub, KEY_ID, SECRET).refundInFull("pay_A", 500, "RF-1");

            assertThat(stub.seen).hasSize(1);
            assertThat(stub.seen.get(0).requestLine()).startsWith("GET");
        }
    }

    @Test
    void aRefusalCarriesTheProvidersReasonButNotTheSecret() throws Exception {
        try (var stub = new BlockingStubServer()) {
            stub.response = BlockingStubServer.json(400, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Authentication failed\"}}");

            assertThatThrownBy(() -> gateway(stub, KEY_ID, SECRET).createOrder(100, "INR", "r", Map.of()))
                .isInstanceOf(PaymentGateway.ProviderException.class)
                .hasMessageContaining("400").hasMessageContaining("Authentication failed")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET));
        }
    }

    @Test
    void aServerErrorWithAnHtmlBodyIsAProviderErrorNotACrash() throws Exception {
        try (var stub = new BlockingStubServer()) {
            stub.response = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/html\r\nContent-Length: 12\r\n\r\n<html>oops!!";

            assertThatThrownBy(() -> gateway(stub, KEY_ID, SECRET).createOrder(100, "INR", "r", Map.of()))
                .isInstanceOf(PaymentGateway.ProviderException.class);
        }
    }

    @Test
    void anAnswerWithoutAnOrderIdIsRefused() throws Exception {
        try (var stub = new BlockingStubServer()) {
            stub.response = BlockingStubServer.json(200, "{\"amount\":100,\"currency\":\"INR\"}");

            assertThatThrownBy(() -> gateway(stub, KEY_ID, SECRET).createOrder(100, "INR", "r", Map.of()))
                .isInstanceOf(PaymentGateway.ProviderException.class).hasMessageContaining("id");
        }
    }

    @Test
    void anUnreachableProviderIsAProviderException() throws Exception {
        BlockingStubServer stub = new BlockingStubServer();
        var gateway = gateway(stub, KEY_ID, SECRET);
        stub.close();

        assertThatThrownBy(() -> gateway.createOrder(100, "INR", "r", Map.of())).isInstanceOf(PaymentGateway.ProviderException.class);
    }

    @Test
    void aSlowProviderTimesOutInsteadOfHanging() throws Exception {
        try (var stub = new BlockingStubServer()) {
            stub.delayMillis = 2500;
            var gateway = new RazorpayGateway(KEY_ID, SECRET, stub.uri() + "/v1", Duration.ofMillis(500), mapper);

            assertThatThrownBy(() -> gateway.createOrder(100, "INR", "r", Map.of())).isInstanceOf(PaymentGateway.ProviderException.class);
        }
    }

    @Test
    void anIdThatIsNotAProviderIdIsNeverPutInAPath() throws Exception {
        try (var stub = new BlockingStubServer()) {
            var gateway = gateway(stub, KEY_ID, SECRET);

            assertThatThrownBy(() -> gateway.paymentsForOrder("../../payments/pay_X")).isInstanceOf(PaymentGateway.ProviderException.class);
            assertThatThrownBy(() -> gateway.fetchPayment("pay_A?x=1")).isInstanceOf(PaymentGateway.ProviderException.class);
            assertThat(stub.seen).isEmpty();
        }
    }
}
