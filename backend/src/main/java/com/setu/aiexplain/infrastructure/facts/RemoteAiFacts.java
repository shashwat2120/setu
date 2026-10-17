package com.setu.aiexplain.infrastructure.facts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.aifacts.AiFacts;
import com.setu.shared.resilience.GuardedCall;
import com.setu.shared.observability.Outbound;
import com.setu.shared.web.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link AiFacts} across the network: the secure tool boundary when the AI advisor runs as its own service (docs/contracts/ai-service.md).
 * <ul>
 *   <li><b>The user's own token travels.</b> Every call presents the bearer token of the person the advisor is talking to, taken from the
 *       authentication of the request (or job) being served. The service that owns the data authenticates it like any other request and
 *       decides with that user's roles, tenant and relationships. This service holds no credential of its own that could read anything, so
 *       a compromised or misled advisor can see no more than the user could.</li>
 *   <li><b>Nothing is invented.</b> A timeout, a refused connection, an error status, an open circuit, a full bulkhead or an answer that is
 *       not JSON all come out as {@link AiFacts.Unavailable}. A refusal by the owner ("not authorized ...") is not a failure: it arrives as a
 *       normal result for the model to report.</li>
 *   <li><b>Bounded.</b> Short timeouts, no retries, a circuit breaker and a bulkhead ({@link GuardedCall}); a 4xx (for example an expired token)
 *       is the caller's problem and does not count against the data service.</li>
 * </ul>
 * Active only when {@code setu.ai-facts.url} is set (the AI service sets it); in one process the facts are answered in-process.
 */
@Component
@ConditionalOnProperty(name = "setu.ai-facts.url")
public class RemoteAiFacts implements AiFacts, DisposableBean {

    /** The owner answered, but with a refusal of the request itself (401, 403, 404, 400): not a sign that it is unwell. */
    private static final class Rejected extends IOException {
        Rejected(String message) {
            super(message);
        }
    }

    private final URI base;
    private final ObjectMapper objectMapper;
    private final GuardedCall guard;
    private final Outbound outbound;
    private final CloseableHttpClient client;

    public RemoteAiFacts(@Value("${setu.ai-facts.url}") String url,
                         @Value("${setu.ai-facts.connect-timeout-ms:500}") long connectMs,
                         @Value("${setu.ai-facts.response-timeout-ms:8000}") long responseMs,
                         @Value("${setu.ai-facts.max-concurrent:20}") int maxConcurrent,
                         @Value("${setu.ai-facts.breaker.minimum-calls:10}") int breakerMinimumCalls,
                         ObjectMapper objectMapper, MeterRegistry meters, Outbound outbound) {
        this.base = URI.create(url.replaceAll("/+$", ""));
        this.objectMapper = objectMapper;
        this.outbound = outbound;
        this.guard = new GuardedCall("ai-facts", new GuardedCall.Settings(50, breakerMinimumCalls, Math.max(20, breakerMinimumCalls), Duration.ofSeconds(10), maxConcurrent, Duration.ZERO),
            e -> !(e instanceof Rejected), meters);
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(64)
                .setMaxConnPerRoute(64)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
                    .setSocketTimeout(Timeout.ofMilliseconds(responseMs))
                    .setValidateAfterInactivity(TimeValue.ofSeconds(1))
                    .build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(responseMs)).build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .evictExpiredConnections()
            .build();
    }

    /** The token of the person being served, from the authentication of the current request or job. */
    private static String callerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getCredentials() instanceof String token) || token.isBlank()) {
            throw new AiFacts.Unavailable("no caller token to present", null);
        }
        return token;
    }

    private JsonNode call(ClassicRequestBuilder request) {
        request.addHeader("Authorization", "Bearer " + callerToken()).addHeader("Accept", "application/json");
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        try {
            return guard.call(() -> outbound.call("data-service", "ai-facts", traceParent -> {
                if (traceParent != null) {
                    request.setHeader("traceparent", traceParent);
                }
                return client.execute(request.build(), response -> {
                int status = response.getCode();
                if (status == HttpStatus.SC_NO_CONTENT) {
                    return objectMapper.nullNode();
                }
                if (status >= 400 && status < 500) {
                    throw new Rejected("the data service refused the request (" + status + ")");
                }
                if (status != HttpStatus.SC_OK) {
                    throw new IOException("unexpected status " + status);
                }
                try {
                    JsonNode body = objectMapper.readTree(EntityUtils.toString(response.getEntity()));
                    if (body == null || body.isMissingNode()) {
                        throw new IOException("empty answer");
                    }
                    return body;
                } catch (JsonProcessingException | org.apache.hc.core5.http.ParseException e) {
                    throw new IOException("the answer is not JSON", e);
                }
                });
            }));
        } catch (GuardedCall.Unavailable e) {
            throw new AiFacts.Unavailable("the data service did not answer (" + e.reason() + ")", e);
        }
    }

    private JsonNode get(String path, String... nameValuePairs) {
        ClassicRequestBuilder request = ClassicRequestBuilder.get(base.resolve("/ai-facts" + path));
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            if (nameValuePairs[i + 1] != null) {
                request.addParameter(nameValuePairs[i], nameValuePairs[i + 1]);
            }
        }
        return call(request);
    }

    @Override
    public Caller caller() {
        JsonNode me = get("/me");
        try {
            JsonNode tenant = me.get("tenantId");
            return new Caller(UUID.fromString(me.get("userId").asText()),
                tenant == null || tenant.isNull() ? null : UUID.fromString(tenant.asText()));
        } catch (RuntimeException e) {
            throw new AiFacts.Unavailable("the data service did not say who is asking", e);
        }
    }

    @Override
    public Object studentAttendance(String studentId) {
        return get("/attendance", "studentId", studentId);
    }

    @Override
    public Object academicProgress(String studentId) {
        return get("/academic-progress", "studentId", studentId);
    }

    @Override
    public Object assessmentResults(String studentId) {
        return get("/assessment-results", "studentId", studentId);
    }

    @Override
    public Object upcomingDeadlines(String studentId) {
        return get("/deadlines", "studentId", studentId);
    }

    @Override
    public Object skillGap(String jobId, String studentId) {
        return get("/skill-gap", "jobId", jobId, "studentId", studentId);
    }

    @Override
    public Object policySearch(String query) {
        return get("/policy-search", "query", query);
    }

    @Override
    public Object learningResources(String studentId) {
        return get("/learning-resources", "studentId", studentId);
    }

    @Override
    public Object transcript(String studentId) {
        return get("/transcript", "studentId", studentId);
    }

    @Override
    public Object feeStatus(String studentId) {
        return get("/fee-status", "studentId", studentId);
    }

    @Override
    public void audit(String action, UUID correlationId, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("correlationId", correlationId);
        body.put("details", details);
        try {
            call(ClassicRequestBuilder.post(base.resolve("/ai-facts/audit"))
                .setEntity(new StringEntity(objectMapper.writeValueAsString(body), ContentType.APPLICATION_JSON)));
        } catch (JsonProcessingException e) {
            throw new AiFacts.Unavailable("the audit record could not be built", e);
        }
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }
}
