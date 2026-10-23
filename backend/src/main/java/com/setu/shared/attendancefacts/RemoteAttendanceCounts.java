package com.setu.shared.attendancefacts;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the Attendance service, over its internal endpoint, for the attendance counts of a set of enrollments. Same discipline as the guardian-grant check
 * ({@code RemoteGuardianGrantVerifier}): a blocking client with short timeouts, wrapped in a {@link GuardedCall} (circuit breaker and bulkhead), the service-to-service token,
 * the correlation id and the trace header. Every way it can fail, including a malformed or incomplete answer, a server error and an unexpected status, comes out as
 * {@link AttendanceCounts.Unavailable}: never zeros, never an old figure standing in for the owner's answer.
 */
public class RemoteAttendanceCounts implements AttendanceCounts, DisposableBean {

    /** Enrollment ids per request: keeps the query string short; a student has a handful of enrollments, a section fan-out asks one at a time. */
    static final int PER_REQUEST = 50;

    private final URI endpoint;
    private final String token;
    private final ObjectMapper objectMapper;
    private final GuardedCall guard;
    private final Outbound outbound;
    private final CloseableHttpClient client;

    public RemoteAttendanceCounts(String baseUrl, String token, Duration connectTimeout, Duration responseTimeout, ObjectMapper objectMapper,
                                  GuardedCall guard, Outbound outbound) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/internal/attendance/counts");
        this.token = token;
        this.objectMapper = objectMapper;
        this.guard = guard;
        this.outbound = outbound;
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
    public Map<UUID, Counts> forEnrollments(Collection<UUID> enrollmentIds) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(enrollmentIds));
        Map<UUID, Counts> all = new HashMap<>();
        for (int from = 0; from < ids.size(); from += PER_REQUEST) {
            all.putAll(ask(ids.subList(from, Math.min(ids.size(), from + PER_REQUEST))));
        }
        return all;
    }

    private Map<UUID, Counts> ask(List<UUID> ids) {
        ClassicRequestBuilder request = ClassicRequestBuilder.get(endpoint)
            .addHeader(InternalCallAuthFilter.HEADER, token)
            .addHeader("Accept", "application/json");
        for (UUID id : ids) {
            request.addParameter("enrollmentIds", id.toString());
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        try {
            return guard.call(() -> outbound.call("attendance", "attendance-counts", traceParent -> {
                if (traceParent != null) {
                    request.setHeader("traceparent", traceParent);
                }
                return client.execute(request.build(), response -> {
                    if (response.getCode() != HttpStatus.SC_OK) {
                        // counts as a failure of the dependency: 401/403 means a misconfigured token, 5xx a sick service
                        throw new IOException("unexpected status " + response.getCode());
                    }
                    return parse(objectMapper.readTree(EntityUtils.toString(response.getEntity())), ids);
                });
            }));
        } catch (GuardedCall.Unavailable e) {
            throw new Unavailable("the Attendance service could not give attendance counts", e);
        }
    }

    /** Every enrollment asked about must be answered, once, with whole numbers: anything else is not an answer. */
    static Map<UUID, Counts> parse(JsonNode body, List<UUID> asked) throws IOException {
        if (body == null || !body.has("counts") || !body.get("counts").isArray()) {
            throw new IOException("malformed answer");
        }
        Map<UUID, Counts> answer = new HashMap<>();
        for (JsonNode row : body.get("counts")) {
            if (!row.hasNonNull("enrollmentId") || !row.path("presentOrLate").isIntegralNumber() || !row.path("total").isIntegralNumber()) {
                throw new IOException("malformed answer");
            }
            long presentOrLate = row.get("presentOrLate").asLong();
            long total = row.get("total").asLong();
            if (presentOrLate < 0 || total < presentOrLate) {
                throw new IOException("impossible counts");
            }
            UUID id;
            try {
                id = UUID.fromString(row.get("enrollmentId").asText());
            } catch (IllegalArgumentException e) {
                throw new IOException("malformed answer", e);
            }
            if (!asked.contains(id) || answer.put(id, new Counts(presentOrLate, total)) != null) {
                throw new IOException("answer names an enrollment that was not asked about, or twice");
            }
        }
        if (answer.size() != asked.size()) {
            throw new IOException("answer is incomplete");
        }
        return answer;
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }
}
