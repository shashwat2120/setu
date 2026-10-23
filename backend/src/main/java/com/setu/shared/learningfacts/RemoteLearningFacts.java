package com.setu.shared.learningfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.internalcall.DependencyUnavailableException;
import com.setu.shared.internalcall.InternalJsonClient;
import com.setu.shared.internalcall.KeyedResults;
import com.setu.shared.observability.Outbound;
import com.setu.shared.resilience.GuardedCall;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Asks the Learning service, over its internal endpoints, for counts, assignments and lessons, through the shared {@link InternalJsonClient}. Every answer must cover every id
 * asked about, once, and be well formed; anything else (and every transport failure) is {@link LearningFacts.Unavailable}, never zeros or an empty list standing in for the
 * owner's answer.
 */
public class RemoteLearningFacts implements LearningFacts, DisposableBean {

    /** Ids per request: keeps the query string short. */
    static final int PER_REQUEST = 50;

    private final InternalJsonClient client;

    public RemoteLearningFacts(String baseUrl, String token, Duration connectTimeout, Duration responseTimeout, ObjectMapper objectMapper,
                               GuardedCall guard, Outbound outbound) {
        this.client = new InternalJsonClient(baseUrl, token, connectTimeout, responseTimeout, objectMapper, guard, outbound, "learning");
    }

    private static <T> T asked(Supplier<T> call) {
        try {
            return call.get();
        } catch (DependencyUnavailableException e) {
            throw new Unavailable(e.getMessage(), e);
        }
    }

    @Override
    public Map<UUID, StudentCounts> studentCounts(Collection<UUID> studentIds) {
        return asked(() -> client.getKeyed("/internal/learning/student-counts", "learning-student-counts", "studentIds", "studentId", studentIds, PER_REQUEST,
            (id, row) -> {
                var counts = new StudentCounts(KeyedResults.count(row, "submissions"), KeyedResults.count(row, "lateSubmissions"),
                    KeyedResults.count(row, "progressRecords"), KeyedResults.count(row, "completedLessons"));
                if (counts.lateSubmissions() > counts.submissions() || counts.completedLessons() > counts.progressRecords()) {
                    throw new IllegalArgumentException("impossible counts");
                }
                return counts;
            }));
    }

    @Override
    public Map<UUID, List<PublishedAssignment>> publishedAssignments(Collection<UUID> sectionIds) {
        return asked(() -> client.getKeyed("/internal/learning/published-assignments", "learning-published-assignments", "sectionIds", "sectionId", sectionIds, PER_REQUEST,
            (id, row) -> list(row.path("assignments"), a -> new PublishedAssignment(UUID.fromString(a.path("id").asText()),
                a.hasNonNull("title") ? a.get("title").asText() : null, a.hasNonNull("dueAt") ? Instant.parse(a.get("dueAt").asText()) : null))));
    }

    @Override
    public Map<UUID, List<LessonRef>> lessonsOfSections(Collection<UUID> sectionIds) {
        return asked(() -> client.getKeyed("/internal/learning/lessons", "learning-lessons", "sectionIds", "sectionId", sectionIds, PER_REQUEST,
            (id, row) -> list(row.path("lessons"), l -> new LessonRef(UUID.fromString(l.path("lessonId").asText()),
                l.hasNonNull("title") ? l.get("title").asText() : null, l.hasNonNull("type") ? l.get("type").asText() : null,
                UUID.fromString(l.path("moduleId").asText())))));
    }

    @Override
    public Map<UUID, Set<UUID>> completedLessons(Collection<UUID> studentIds) {
        return asked(() -> client.getKeyed("/internal/learning/completed-lessons", "learning-completed-lessons", "studentIds", "studentId", studentIds, PER_REQUEST,
            (id, row) -> Set.copyOf(new LinkedHashSet<>(list(row.path("lessonIds"), n -> UUID.fromString(n.asText()))))));
    }

    private static <T> List<T> list(JsonNode array, java.util.function.Function<JsonNode, T> read) {
        if (!array.isArray()) {
            throw new IllegalArgumentException("not a list");
        }
        List<T> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(read.apply(n));
        }
        return List.copyOf(out);
    }

    @Override
    public void destroy() throws IOException {
        client.destroy();
    }
}
