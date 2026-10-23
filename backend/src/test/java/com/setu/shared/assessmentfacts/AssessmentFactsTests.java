package com.setu.shared.assessmentfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.assessment.application.AssessmentComponentService;
import com.setu.assessment.application.AssessmentService;
import com.setu.assessment.application.GradeService;
import com.setu.assessment.application.LocalAssessmentFacts;
import com.setu.assessment.domain.Assessment;
import com.setu.assessment.domain.Grade;
import com.setu.shared.assessmentfacts.AssessmentFacts.GradeFact;
import com.setu.shared.observability.Outbound;
import com.setu.shared.resilience.GuardedCall;
import com.setu.testsupport.BlockingStubServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The grades and assessments the risk engine, the AI advisor and the academic record ask for come from the Assessment service once it has a database of its own. These tests
 * hold what the remote reader promises: the owner's data comes through exactly (a 3.50 stays 3.50, a null stays null), the request is authenticated and asks for what was
 * meant, and anything that is not a complete, well-formed answer is {@link AssessmentFacts.Unavailable}, never an empty list standing in for it. No database, no Spring context.
 */
class AssessmentFactsTests {

    private static final UUID E1 = UUID.randomUUID();
    private static final UUID E2 = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID C1 = UUID.randomUUID();

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private BlockingStubServer owner;

    @BeforeEach
    void startOwner() throws Exception {
        owner = new BlockingStubServer();
    }

    @AfterEach
    void stopOwner() throws Exception {
        owner.close();
    }

    private static void answer(BlockingStubServer server, int status, String body) {
        server.response = "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
    }

    private RemoteAssessmentFacts remote(long responseMs) {
        return new RemoteAssessmentFacts(owner.uri().toString(), "s3cret", Duration.ofMillis(500), Duration.ofMillis(responseMs), mapper,
            new GuardedCall("assessment-facts", new GuardedCall.Settings(50, 10, 20, Duration.ofSeconds(10), 20, Duration.ZERO), e -> true, meters), Outbound.NONE);
    }

    private static String grades(UUID enrollmentId, String gradesJson) {
        return "{\"enrollmentId\":\"" + enrollmentId + "\",\"grades\":" + gradesJson + "}";
    }

    private static String grade(UUID componentId) {
        return "{\"componentId\":\"" + componentId + "\",\"rawMarks\":72.5,\"gradeCode\":\"B\",\"gradePoint\":3.50,\"assessmentTitle\":\"Midterm\",\"componentCode\":\"MT\",\"weight\":0.4}";
    }

    @Test
    void theOwnersGradesComeThroughExactlyAndTheRequestCarriesTheTokenAndEveryEnrollment() {
        answer(owner, 200, "{\"results\":[" + grades(E1, "[" + grade(C1) + "]") + "," + grades(E2, "[]") + "]}");

        Map<UUID, List<GradeFact>> found = remote(800).currentGrades(List.of(E1, E2));

        assertThat(found.get(E2)).as("none is an empty list, and still an answer").isEmpty();
        GradeFact g = found.get(E1).get(0);
        assertThat(g.enrollmentId()).isEqualTo(E1);
        assertThat(g.componentId()).isEqualTo(C1);
        assertThat(g.gradeCode()).isEqualTo("B");
        assertThat(g.gradePoint()).isEqualByComparingTo("3.50");
        assertThat(g.rawMarks()).isEqualByComparingTo("72.5");
        assertThat(g.weight()).isEqualByComparingTo("0.4");
        assertThat(g.assessmentTitle()).isEqualTo("Midterm");
        assertThat(g.componentCode()).isEqualTo("MT");
        var seen = owner.seen.get(0);
        assertThat(seen.requestLine()).startsWith("GET /internal/assessment/grades?").contains("enrollmentIds=" + E1).contains("enrollmentIds=" + E2);
        assertThat(seen.header("X-Internal-Token")).isEqualTo("s3cret");
    }

    @Test
    void aGradeWithNothingOptionalStaysNullNotZeroOrEmpty() {
        answer(owner, 200, "{\"results\":[" + grades(E1, "[{\"componentId\":\"" + C1 + "\",\"rawMarks\":null,\"gradeCode\":null,\"gradePoint\":null,\"assessmentTitle\":null,\"componentCode\":null,\"weight\":null}]") + "]}");

        GradeFact g = remote(800).currentGrades(List.of(E1)).get(E1).get(0);

        assertThat(g.gradePoint()).isNull();
        assertThat(g.weight()).isNull();
        assertThat(g.gradeCode()).isNull();
        assertThat(g.assessmentTitle()).isNull();
    }

    @Test
    void theVisibleAssessmentsOfASectionComeThroughWithTheirStart() {
        Instant start = Instant.parse("2026-11-02T09:30:00Z");
        answer(owner, 200, "{\"results\":[{\"sectionId\":\"" + S1 + "\",\"assessments\":[{\"id\":\"" + C1 + "\",\"title\":\"Quiz 2\",\"startsAt\":\"" + start + "\"}]}]}");

        var found = remote(800).visibleAssessments(List.of(S1));

        assertThat(found.get(S1)).singleElement().satisfies(a -> {
            assertThat(a.title()).isEqualTo("Quiz 2");
            assertThat(a.startsAt()).isEqualTo(start);
        });
        assertThat(owner.seen.get(0).requestLine()).startsWith("GET /internal/assessment/visible?").contains("sectionIds=" + S1);
    }

    @Test
    void aLongListIsAskedInSeveralRequestsAndPutBackTogether() {
        List<UUID> ids = IntStream.range(0, RemoteAssessmentFacts.PER_REQUEST * 2 + 1).mapToObj(i -> UUID.randomUUID()).toList();
        owner.responder = seen -> {
            StringBuilder rows = new StringBuilder();
            for (UUID id : ids) {
                if (seen.requestLine().contains(id.toString())) {
                    rows.append(rows.isEmpty() ? "" : ",").append(grades(id, "[]"));
                }
            }
            String body = "{\"results\":[" + rows + "]}";
            return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        };

        assertThat(remote(800).currentGrades(ids)).hasSize(ids.size());
        assertThat(owner.seen).hasSize(3);
    }

    @Test
    void anythingButAnOkAnswerIsUnavailableNotAnEmptyList() {
        var reader = remote(800);
        for (int status : new int[] {401, 403, 404, 500, 503}) {
            answer(owner, status, "{}");
            assertThatThrownBy(() -> reader.currentGrades(List.of(E1))).as("grades, status " + status).isInstanceOf(AssessmentFacts.Unavailable.class);
            assertThatThrownBy(() -> reader.visibleAssessments(List.of(S1))).as("visible, status " + status).isInstanceOf(AssessmentFacts.Unavailable.class);
        }
    }

    @Test
    void anAnswerThatIsNotCompleteAndWellFormedIsUnavailable() {
        var reader = remote(800);
        List<String> unusable = List.of(
            "not json",
            "{}",
            "{\"results\":{}}",
            "{\"results\":[]}",                                                           // asked about E1, told nothing
            "{\"results\":[" + grades(E2, "[]") + "]}",                                   // told about someone else
            "{\"results\":[" + grades(E1, "[]") + "," + grades(E1, "[]") + "]}",          // the same one twice
            "{\"results\":[{\"enrollmentId\":\"nope\",\"grades\":[]}]}",
            "{\"results\":[{\"enrollmentId\":\"" + E1 + "\",\"grades\":{}}]}",
            "{\"results\":[{\"enrollmentId\":\"" + E1 + "\"}]}",
            "{\"results\":[" + grades(E1, "[{\"componentId\":\"x\"}]") + "]}",
            "{\"results\":[" + grades(E1, "[{\"componentId\":\"" + C1 + "\",\"gradePoint\":\"3\"}]") + "]}");
        for (String body : unusable) {
            answer(owner, 200, body);
            assertThatThrownBy(() -> reader.currentGrades(List.of(E1))).as(body).isInstanceOf(AssessmentFacts.Unavailable.class);
        }
        answer(owner, 200, "{\"results\":[{\"sectionId\":\"" + S1 + "\",\"assessments\":[{\"id\":\"" + C1 + "\",\"title\":\"Q\",\"startsAt\":\"yesterday\"}]}]}");
        assertThatThrownBy(() -> reader.visibleAssessments(List.of(S1))).as("a start that is not a time").isInstanceOf(AssessmentFacts.Unavailable.class);
    }

    @Test
    void anOwnerThatDoesNotAnswerInTimeIsUnavailable() {
        owner.dropWithoutAnswer = true;

        assertThatThrownBy(() -> remote(200).currentGrades(List.of(E1))).isInstanceOf(AssessmentFacts.Unavailable.class);
    }

    @Test
    void withoutAnyIdsNobodyIsAsked() {
        assertThat(remote(800).currentGrades(List.of())).isEmpty();
        assertThat(remote(800).visibleAssessments(List.of())).isEmpty();
        assertThat(owner.seen).isEmpty();
    }

    @Test
    void insideTheAssessmentModuleTheFactsAreTheModulesOwnGradesNamedByTheirComponents() {
        var gradeService = Mockito.mock(GradeService.class);
        var components = Mockito.mock(AssessmentComponentService.class);
        var assessments = Mockito.mock(AssessmentService.class);
        var grade = Mockito.mock(Grade.class);
        Mockito.when(grade.componentId()).thenReturn(C1);
        Mockito.when(grade.rawMarks()).thenReturn(new BigDecimal("80"));
        Mockito.when(grade.gradeCode()).thenReturn("A");
        Mockito.when(grade.gradePoint()).thenReturn(new BigDecimal("4.0"));
        Mockito.when(gradeService.findCurrentForEnrollment(E1)).thenReturn(List.of(grade));
        Mockito.when(gradeService.findCurrentForEnrollment(E2)).thenReturn(List.of());
        Mockito.when(components.infoUnchecked(List.of(C1))).thenReturn(Map.of(C1, new AssessmentComponentService.Info("Final", "FIN", new BigDecimal("0.6"))));
        var assessment = Mockito.mock(Assessment.class);
        Mockito.when(assessment.id()).thenReturn(C1);
        Mockito.when(assessment.title()).thenReturn("Quiz");
        Mockito.when(assessment.startsAt()).thenReturn(Instant.parse("2026-12-01T10:00:00Z"));
        Mockito.when(assessments.listVisibleForSection(S1)).thenReturn(List.of(assessment));
        var facts = new LocalAssessmentFacts(gradeService, components, assessments);

        var found = facts.currentGrades(List.of(E1, E2, E1));

        assertThat(found).containsOnlyKeys(E1, E2);
        assertThat(found.get(E2)).isEmpty();
        assertThat(found.get(E1)).singleElement().satisfies(g -> {
            assertThat(g.enrollmentId()).isEqualTo(E1);
            assertThat(g.assessmentTitle()).isEqualTo("Final");
            assertThat(g.componentCode()).isEqualTo("FIN");
            assertThat(g.weight()).isEqualByComparingTo("0.6");
            assertThat(g.gradePoint()).isEqualByComparingTo("4.0");
        });
        assertThat(facts.visibleAssessments(List.of(S1)).get(S1)).singleElement().satisfies(a -> assertThat(a.title()).isEqualTo("Quiz"));
    }
}
