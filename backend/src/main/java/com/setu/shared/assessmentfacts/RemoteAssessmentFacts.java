package com.setu.shared.assessmentfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.internalcall.DependencyUnavailableException;
import com.setu.shared.internalcall.InternalJsonClient;
import com.setu.shared.observability.Outbound;
import com.setu.shared.resilience.GuardedCall;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Asks the Assessment service, over its internal endpoints, for grades and for student-visible assessments, through the shared {@link InternalJsonClient}. Every answer must
 * cover every id asked about, once, and be well formed; anything else (and every transport failure) is {@link AssessmentFacts.Unavailable}, never an empty list standing in
 * for the owner's answer.
 */
public class RemoteAssessmentFacts implements AssessmentFacts, DisposableBean {

    /** Ids per request: keeps the query string short. */
    static final int PER_REQUEST = 50;

    private final InternalJsonClient client;

    public RemoteAssessmentFacts(String baseUrl, String token, Duration connectTimeout, Duration responseTimeout, ObjectMapper objectMapper,
                                 GuardedCall guard, Outbound outbound) {
        this.client = new InternalJsonClient(baseUrl, token, connectTimeout, responseTimeout, objectMapper, guard, outbound, "assessment");
    }

    @Override
    public Map<UUID, List<GradeFact>> currentGrades(Collection<UUID> enrollmentIds) {
        return ask("/internal/assessment/grades", "assessment-grades", "enrollmentIds", "enrollmentId", enrollmentIds,
            (id, row) -> gradesOf(id, row.path("grades")));
    }

    @Override
    public Map<UUID, List<VisibleAssessment>> visibleAssessments(Collection<UUID> sectionIds) {
        return ask("/internal/assessment/visible", "assessment-visible", "sectionIds", "sectionId", sectionIds,
            (id, row) -> visibleOf(row.path("assessments")));
    }

    private <T> Map<UUID, List<T>> ask(String path, String operation, String param, String idField, Collection<UUID> idsAsked,
                                       BiFunction<UUID, JsonNode, List<T>> rowReader) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(idsAsked));
        Map<UUID, List<T>> all = new HashMap<>();
        for (int from = 0; from < ids.size(); from += PER_REQUEST) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + PER_REQUEST));
            try {
                all.putAll(client.get(path, operation, param, chunk, body -> parse(body, chunk, idField, rowReader)));
            } catch (DependencyUnavailableException e) {
                throw new Unavailable(e.getMessage(), e);
            }
        }
        return all;
    }

    /** One entry per id asked about, each id once, nothing else: anything different is not an answer. */
    static <T> Map<UUID, List<T>> parse(JsonNode body, List<UUID> asked, String idField, BiFunction<UUID, JsonNode, List<T>> rowReader) throws IOException {
        if (body == null || !body.has("results") || !body.get("results").isArray()) {
            throw new IOException("malformed answer");
        }
        Map<UUID, List<T>> answer = new HashMap<>();
        for (JsonNode row : body.get("results")) {
            UUID id;
            try {
                id = UUID.fromString(row.path(idField).asText());
            } catch (IllegalArgumentException e) {
                throw new IOException("malformed answer", e);
            }
            List<T> read;
            try {
                read = rowReader.apply(id, row);
            } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException e) {
                throw new IOException("malformed answer", e);
            }
            if (!asked.contains(id) || answer.put(id, read) != null) {
                throw new IOException("answer names an id that was not asked about, or twice");
            }
        }
        if (answer.size() != asked.size()) {
            throw new IOException("answer is incomplete");
        }
        return answer;
    }

    private static List<GradeFact> gradesOf(UUID enrollmentId, JsonNode grades) {
        if (!grades.isArray()) {
            throw new IllegalArgumentException("grades is not a list");
        }
        List<GradeFact> out = new ArrayList<>();
        for (JsonNode g : grades) {
            UUID componentId = UUID.fromString(g.path("componentId").asText());
            String gradeCode = g.hasNonNull("gradeCode") ? g.get("gradeCode").asText() : null;
            out.add(new GradeFact(enrollmentId, componentId, number(g, "rawMarks"), gradeCode, number(g, "gradePoint"),
                g.hasNonNull("assessmentTitle") ? g.get("assessmentTitle").asText() : null,
                g.hasNonNull("componentCode") ? g.get("componentCode").asText() : null, number(g, "weight")));
        }
        return List.copyOf(out);
    }

    private static List<VisibleAssessment> visibleOf(JsonNode assessments) {
        if (!assessments.isArray()) {
            throw new IllegalArgumentException("assessments is not a list");
        }
        List<VisibleAssessment> out = new ArrayList<>();
        for (JsonNode a : assessments) {
            out.add(new VisibleAssessment(UUID.fromString(a.path("id").asText()), a.hasNonNull("title") ? a.get("title").asText() : null,
                a.hasNonNull("startsAt") ? Instant.parse(a.get("startsAt").asText()) : null));
        }
        return List.copyOf(out);
    }

    /** A number as written (so 3.50 stays 3.50), null when absent; anything that is not a number is not an answer. */
    private static BigDecimal number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isNumber()) {
            throw new IllegalArgumentException(field + " is not a number");
        }
        return v.decimalValue();
    }

    @Override
    public void destroy() throws IOException {
        client.destroy();
    }
}
