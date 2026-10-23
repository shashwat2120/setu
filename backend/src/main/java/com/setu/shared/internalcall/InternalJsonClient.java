package com.setu.shared.internalcall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.observability.Outbound;
import com.setu.shared.resilience.GuardedCall;
import com.setu.shared.security.InternalCallAuthFilter;
import com.setu.shared.web.CorrelationIdFilter;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
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
import java.util.Collection;
import java.util.UUID;

/**
 * One service's questions to another over an {@code /internal/**} endpoint: a blocking client with short timeouts, wrapped in a {@link GuardedCall} (circuit breaker and
 * bulkhead), carrying the service-to-service token, the correlation id and the trace header. The same discipline as the guardian-grant check
 * ({@code RemoteGuardianGrantVerifier}). Every way a call can fail, including an unexpected status and an answer the caller's parser rejects, comes out as
 * {@link DependencyUnavailableException}: never an empty answer standing in for the owner's.
 */
public class InternalJsonClient implements DisposableBean {

    /** Reads the answer; throws {@link IOException} for anything that is not a complete, plausible answer to what was asked. */
    @FunctionalInterface
    public interface Parser<T> {
        T parse(JsonNode body) throws IOException;
    }

    private final URI base;
    private final String token;
    private final ObjectMapper objectMapper;
    private final GuardedCall guard;
    private final Outbound outbound;
    private final String peer;
    private final CloseableHttpClient client;

    public InternalJsonClient(String baseUrl, String token, Duration connectTimeout, Duration responseTimeout, ObjectMapper objectMapper,
                              GuardedCall guard, Outbound outbound, String peer) {
        this.base = URI.create(baseUrl.replaceAll("/+$", ""));
        this.token = token;
        this.objectMapper = objectMapper;
        this.guard = guard;
        this.outbound = outbound;
        this.peer = peer;
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

    /** {@code GET <base><path>?<param>=<id>&<param>=<id>...}, the answer read by {@code parser}. */
    public <T> T get(String path, String operation, String param, Collection<UUID> ids, Parser<T> parser) {
        ClassicRequestBuilder request = ClassicRequestBuilder.get(URI.create(base + path))
            .addHeader(InternalCallAuthFilter.HEADER, token)
            .addHeader("Accept", "application/json");
        for (UUID id : ids) {
            request.addParameter(param, id.toString());
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        try {
            return guard.call(() -> outbound.call(peer, operation, traceParent -> {
                if (traceParent != null) {
                    request.setHeader("traceparent", traceParent);
                }
                return client.execute(request.build(), response -> {
                    if (response.getCode() != HttpStatus.SC_OK) {
                        // counts as a failure of the dependency: 401/403 means a misconfigured token, 5xx a sick service
                        throw new IOException("unexpected status " + response.getCode());
                    }
                    return parser.parse(objectMapper.readTree(EntityUtils.toString(response.getEntity())));
                });
            }));
        } catch (GuardedCall.Unavailable e) {
            throw new DependencyUnavailableException("the " + peer + " service could not answer " + operation, e);
        }
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }
}
