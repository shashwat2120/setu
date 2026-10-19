package com.setu.finance.infrastructure.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.finance.application.PaymentGateway;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Razorpay's REST API (https://razorpay.com/docs/api/), over the JDK HTTP client with HTTP Basic auth ({@code key_id:key_secret}). Test-mode and
 * live-mode keys differ only by their {@code rzp_test_} / {@code rzp_live_} prefix; this class does not care which it holds.
 *
 * <p>Nothing here ever sees or sends a card number: the student's browser sends those straight to Razorpay's checkout. The secret is read once into
 * the Authorization header and is never logged or put in an exception message.
 */
@Component
public class RazorpayGateway implements PaymentGateway, DisposableBean {

    private final String keyId;
    private final String authorization;
    private final URI baseUri;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient client;

    public RazorpayGateway(@Value("${app.finance.razorpay.key-id:}") String keyId,
                           @Value("${app.finance.razorpay.key-secret:}") String keySecret,
                           @Value("${app.finance.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl,
                           @Value("${app.finance.razorpay.timeout:10s}") Duration timeout,
                           ObjectMapper objectMapper) {
        this.keyId = keyId == null ? "" : keyId.trim();
        String secret = keySecret == null ? "" : keySecret.trim();
        this.authorization = this.keyId.isEmpty() || secret.isEmpty() ? null
            : "Basic " + Base64.getEncoder().encodeToString((this.keyId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        this.baseUri = URI.create(baseUrl.replaceAll("/+$", "") + "/");
        this.objectMapper = objectMapper;
        // Blocking Apache client, as everywhere else in this codebase: the JDK client needs NIO selectors, which do not start on the Windows dev machine.
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(8).setMaxConnPerRoute(8)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.of(timeout)).build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(timeout)).build())
            .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build();
    }

    @Override
    public boolean enabled() {
        return authorization != null;
    }

    @Override
    public String publicKeyId() {
        if (!enabled()) {
            throw new NotConfiguredException();
        }
        return keyId;
    }

    @Override
    public Order createOrder(long amountMinor, String currency, String receipt, Map<String, String> notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountMinor);
        body.put("currency", currency);
        body.put("receipt", receipt);
        body.put("notes", notes);
        JsonNode order = call("POST", "orders", body);
        return new Order(text(order, "id"), order.path("amount").asLong(-1), text(order, "currency"));
    }

    @Override
    public List<ProviderPayment> paymentsForOrder(String orderId) {
        JsonNode result = call("GET", "orders/" + segment(orderId) + "/payments", null);
        List<ProviderPayment> payments = new ArrayList<>();
        for (JsonNode item : result.path("items")) {
            payments.add(payment(item));
        }
        return payments;
    }

    @Override
    public ProviderPayment fetchPayment(String providerPaymentId) {
        return payment(call("GET", "payments/" + segment(providerPaymentId), null));
    }

    @Override
    public void refundInFull(String providerPaymentId, long amountMinor, String receipt) {
        // A retry after a refund that went through but was not recorded here must not refund twice.
        if (fetchPayment(providerPaymentId).refunded()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountMinor);
        body.put("speed", "normal");
        body.put("receipt", receipt);
        call("POST", "payments/" + segment(providerPaymentId) + "/refund", body);
    }

    private ProviderPayment payment(JsonNode node) {
        return new ProviderPayment(text(node, "id"), node.path("order_id").asText(null), text(node, "status"), node.path("amount").asLong(-1), text(node, "currency"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new ProviderException("the payment provider's answer has no " + field);
        }
        return value.asText();
    }

    /** Provider ids are {@code order_...} / {@code pay_...}: refuse anything else instead of building a path from it. */
    private static String segment(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_]{1,64}")) {
            throw new ProviderException("not a provider id");
        }
        return id;
    }

    private record Answer(int status, String body) {
    }

    private JsonNode call(String method, String path, Object body) {
        if (!enabled()) {
            throw new NotConfiguredException();
        }
        try {
            ClassicRequestBuilder request = ClassicRequestBuilder.create(method).setUri(baseUri.resolve(path))
                .addHeader("Authorization", authorization).addHeader("Accept", "application/json");
            if (body != null) {
                request.setEntity(new StringEntity(objectMapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
            }
            Answer answer = client.execute(request.build(), response -> new Answer(response.getCode(), EntityUtils.toString(response.getEntity())));
            JsonNode json = parse(answer.body());
            if (answer.status() / 100 != 2) {
                String why = json.path("error").path("description").asText("no reason given");
                throw new ProviderException("the payment provider refused the request (" + answer.status() + "): " + why);
            }
            return json;
        } catch (IOException e) {
            throw new ProviderException("the payment provider could not be reached", e);
        }
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw new ProviderException("the payment provider's answer was not JSON", e);
        }
    }
}
