package com.setu.shared.attendancefacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.internalcall.DependencyUnavailableException;
import com.setu.shared.internalcall.InternalJsonClient;
import com.setu.shared.observability.Outbound;
import com.setu.shared.resilience.GuardedCall;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the Attendance service, over its internal endpoint, for the attendance counts of a set of enrollments, through the shared {@link InternalJsonClient} (token, timeouts,
 * circuit breaker, bulkhead, correlation and trace headers). Every way it can fail, including a malformed or incomplete answer, a server error and an unexpected status, comes
 * out as {@link AttendanceCounts.Unavailable}: never zeros, never an old figure standing in for the owner's answer.
 */
public class RemoteAttendanceCounts implements AttendanceCounts, DisposableBean {

    /** Enrollment ids per request: keeps the query string short; a student has a handful of enrollments, a section fan-out asks one at a time. */
    static final int PER_REQUEST = 50;

    private final InternalJsonClient client;

    public RemoteAttendanceCounts(String baseUrl, String token, Duration connectTimeout, Duration responseTimeout, ObjectMapper objectMapper,
                                  GuardedCall guard, Outbound outbound) {
        this.client = new InternalJsonClient(baseUrl, token, connectTimeout, responseTimeout, objectMapper, guard, outbound, "attendance");
    }

    @Override
    public Map<UUID, Counts> forEnrollments(Collection<UUID> enrollmentIds) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(enrollmentIds));
        Map<UUID, Counts> all = new HashMap<>();
        for (int from = 0; from < ids.size(); from += PER_REQUEST) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + PER_REQUEST));
            try {
                all.putAll(client.get("/internal/attendance/counts", "attendance-counts", "enrollmentIds", chunk, body -> parse(body, chunk)));
            } catch (DependencyUnavailableException e) {
                throw new Unavailable(e.getMessage(), e);
            }
        }
        return all;
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
        client.destroy();
    }
}
