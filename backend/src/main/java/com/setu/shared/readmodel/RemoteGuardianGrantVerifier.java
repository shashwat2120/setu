package com.setu.shared.readmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.resilience.GuardedCall;
import com.setu.shared.security.InternalCallAuthFilter;
import com.setu.shared.web.CorrelationIdFilter;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Asks Academic, over its internal endpoint, whether a guardian grant is active right now. Blocking client, short timeouts, and wrapped in
 * a {@link GuardedCall} (circuit breaker and bulkhead); every way it can fail, including a malformed answer, a server error and an
 * unexpected status, comes out as {@link Verdict#UNAVAILABLE}, never as a yes or a no that the owner did not give.
 */
public class RemoteGuardianGrantVerifier implements GuardianGrantVerifier, DisposableBean {

    private final URI endpoint;
    private final String token;
    private final ObjectMapper objectMapper;
    private final GuardedCall guard;
    private final CloseableHttpClient client;

    public RemoteGuardianGrantVerifier(String baseUrl, String token, Duration connectTimeout, Duration responseTimeout, ObjectMapper objectMapper,
                                       GuardedCall guard) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/internal/guardian-grants/verify");
        this.token = token;
        this.objectMapper = objectMapper;
        this.guard = guard;
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(32)
                .setMaxConnPerRoute(32)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(connectTimeout))
                    .setSocketTimeout(Timeout.of(responseTimeout))
                    .setValidateAfterInactivity(TimeValue.ofSeconds(1))
                    .build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(responseTimeout)).build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .evictExpiredConnections()
            .build();
    }

    @Override
    public Verdict verify(UUID guardianUserId, UUID studentProfileId, UUID tenantId, String permissionCode) {
        ClassicRequestBuilder request = ClassicRequestBuilder.get(endpoint)
            .addParameter("guardianUserId", guardianUserId.toString())
            .addParameter("studentProfileId", studentProfileId.toString())
            .addParameter("tenantId", tenantId.toString())
            .addParameter("permission", permissionCode)
            .addHeader(InternalCallAuthFilter.HEADER, token)
            .addHeader("Accept", "application/json");
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        ClassicHttpRequest built = request.build();
        try {
            boolean active = guard.call(() -> client.execute(built, response -> {
                if (response.getCode() != HttpStatus.SC_OK) {
                    // counts as a failure of the dependency: 401/403 means a misconfigured token, 5xx a sick service
                    throw new IOException("unexpected status " + response.getCode());
                }
                JsonNode body = objectMapper.readTree(EntityUtils.toString(response.getEntity()));
                if (body == null || !body.has("active") || !body.get("active").isBoolean()) {
                    throw new IOException("malformed answer");
                }
                return body.get("active").asBoolean();
            }));
            return active ? Verdict.ACTIVE : Verdict.NOT_ACTIVE;
        } catch (GuardedCall.Unavailable e) {
            return Verdict.UNAVAILABLE;
        }
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }
}
