package com.setu.shared.learningfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.learning.application.AssignmentService;
import com.setu.learning.application.AssignmentSubmissionService;
import com.setu.learning.application.CourseModuleService;
import com.setu.learning.application.LearningProgressService;
import com.setu.learning.application.LessonService;
import com.setu.learning.application.LocalLearningFacts;
import com.setu.learning.domain.Assignment;
import com.setu.learning.domain.AssignmentSubmission;
import com.setu.learning.domain.AssignmentSubmissionStatus;
import com.setu.learning.domain.CourseModule;
import com.setu.learning.domain.LearningProgress;
import com.setu.learning.domain.LearningProgressState;
import com.setu.learning.domain.Lesson;
import com.setu.learning.domain.LessonType;
import com.setu.shared.learningfacts.LearningFacts.StudentCounts;
import com.setu.shared.observability.Outbound;
import com.setu.shared.resilience.GuardedCall;
import com.setu.testsupport.BlockingStubServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The learning data the risk engine and the AI advisor ask for comes from the Learning service once it has a database of its own. These tests hold what the remote reader
 * promises: the owner's numbers and lists come through exactly, the request is authenticated and asks for what was meant, and anything that is not a complete, well-formed,
 * plausible answer is {@link LearningFacts.Unavailable}, never zeros or an empty list standing in for it. No database, no Spring context.
 */
class LearningFactsTests {

    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID SECTION = UUID.randomUUID();
    private static final UUID L1 = UUID.randomUUID();
    private static final UUID L2 = UUID.randomUUID();
    private static final UUID MODULE = UUID.randomUUID();

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

    private RemoteLearningFacts remote(long responseMs) {
        return new RemoteLearningFacts(owner.uri().toString(), "s3cret", Duration.ofMillis(500), Duration.ofMillis(responseMs), mapper,
            new GuardedCall("learning-facts", new GuardedCall.Settings(50, 10, 20, Duration.ofSeconds(10), 20, Duration.ZERO), e -> true, meters), Outbound.NONE);
    }

    private static String counts(UUID student, long submissions, long late, long progress, long completed) {
        return "{\"studentId\":\"" + student + "\",\"submissions\":" + submissions + ",\"lateSubmissions\":" + late + ",\"progressRecords\":" + progress + ",\"completedLessons\":" + completed + "}";
    }

    @Test
    void theOwnersCountsComeThroughAndTheRequestCarriesTheTokenAndEveryStudent() {
        answer(owner, 200, "{\"results\":[" + counts(STUDENT, 5, 2, 10, 4) + "," + counts(OTHER, 0, 0, 0, 0) + "]}");

        var found = remote(800).studentCounts(List.of(STUDENT, OTHER));

        assertThat(found.get(STUDENT)).isEqualTo(new StudentCounts(5, 2, 10, 4));
        assertThat(found.get(OTHER)).as("none is zero, and still an answer").isEqualTo(StudentCounts.NONE);
        var seen = owner.seen.get(0);
        assertThat(seen.requestLine()).startsWith("GET /internal/learning/student-counts?").contains("studentIds=" + STUDENT).contains("studentIds=" + OTHER);
        assertThat(seen.header("X-Internal-Token")).isEqualTo("s3cret");
    }

    @Test
    void publishedAssignmentsLessonsAndCompletedLessonsComeThroughWithTheirDetail() {
        Instant due = Instant.parse("2026-11-20T18:00:00Z");
        answer(owner, 200, "{\"results\":[{\"sectionId\":\"" + SECTION + "\",\"assignments\":[{\"id\":\"" + L1 + "\",\"title\":\"Lab 3\",\"dueAt\":\"" + due + "\"},{\"id\":\"" + L2 + "\",\"title\":null,\"dueAt\":null}]}]}");
        var assignments = remote(800).publishedAssignments(List.of(SECTION)).get(SECTION);
        assertThat(assignments).hasSize(2);
        assertThat(assignments.get(0).title()).isEqualTo("Lab 3");
        assertThat(assignments.get(0).dueAt()).isEqualTo(due);
        assertThat(assignments.get(1).dueAt()).as("no due date stays none").isNull();

        answer(owner, 200, "{\"results\":[{\"sectionId\":\"" + SECTION + "\",\"lessons\":[{\"lessonId\":\"" + L1 + "\",\"title\":\"Intro\",\"type\":\"VIDEO\",\"moduleId\":\"" + MODULE + "\"}]}]}");
        assertThat(remote(800).lessonsOfSections(List.of(SECTION)).get(SECTION)).singleElement()
            .isEqualTo(new LearningFacts.LessonRef(L1, "Intro", "VIDEO", MODULE));

        answer(owner, 200, "{\"results\":[{\"studentId\":\"" + STUDENT + "\",\"lessonIds\":[\"" + L1 + "\",\"" + L2 + "\"]}]}");
        assertThat(remote(800).completedLessons(List.of(STUDENT)).get(STUDENT)).containsExactlyInAnyOrder(L1, L2);
    }

    @Test
    void aLongListIsAskedInSeveralRequestsAndPutBackTogether() {
        List<UUID> ids = IntStream.range(0, RemoteLearningFacts.PER_REQUEST * 2 + 1).mapToObj(i -> UUID.randomUUID()).toList();
        owner.responder = seen -> {
            StringBuilder rows = new StringBuilder();
            for (UUID id : ids) {
                if (seen.requestLine().contains(id.toString())) {
                    rows.append(rows.isEmpty() ? "" : ",").append(counts(id, 1, 0, 1, 1));
                }
            }
            String body = "{\"results\":[" + rows + "]}";
            return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        };

        assertThat(remote(800).studentCounts(ids)).hasSize(ids.size());
        assertThat(owner.seen).hasSize(3);
    }

    @Test
    void anythingButAnOkAnswerIsUnavailableNotZeros() {
        var reader = remote(800);
        for (int status : new int[] {401, 403, 404, 500, 503}) {
            answer(owner, status, "{}");
            assertThatThrownBy(() -> reader.studentCounts(List.of(STUDENT))).as("counts, status " + status).isInstanceOf(LearningFacts.Unavailable.class);
            assertThatThrownBy(() -> reader.publishedAssignments(List.of(SECTION))).as("assignments, status " + status).isInstanceOf(LearningFacts.Unavailable.class);
            assertThatThrownBy(() -> reader.lessonsOfSections(List.of(SECTION))).as("lessons, status " + status).isInstanceOf(LearningFacts.Unavailable.class);
            assertThatThrownBy(() -> reader.completedLessons(List.of(STUDENT))).as("completed, status " + status).isInstanceOf(LearningFacts.Unavailable.class);
        }
    }

    @Test
    void anAnswerThatIsNotCompleteWellFormedAndPlausibleIsUnavailable() {
        var reader = remote(800);
        List<String> unusable = List.of(
            "not json",
            "{}",
            "{\"results\":{}}",
            "{\"results\":[]}",                                                                    // asked about STUDENT, told nothing
            "{\"results\":[" + counts(OTHER, 1, 0, 1, 0) + "]}",                                   // told about someone else
            "{\"results\":[" + counts(STUDENT, 1, 0, 1, 0) + "," + counts(STUDENT, 1, 0, 1, 0) + "]}",   // the same one twice
            "{\"results\":[" + counts(STUDENT, 2, 3, 1, 0) + "]}",                                 // more late than submitted
            "{\"results\":[" + counts(STUDENT, 2, 0, 1, 5) + "]}",                                 // more completed than started
            "{\"results\":[" + counts(STUDENT, -1, 0, 1, 0) + "]}",
            "{\"results\":[{\"studentId\":\"" + STUDENT + "\",\"submissions\":\"2\",\"lateSubmissions\":0,\"progressRecords\":1,\"completedLessons\":0}]}",
            "{\"results\":[{\"studentId\":\"" + STUDENT + "\",\"submissions\":2}]}");
        for (String body : unusable) {
            answer(owner, 200, body);
            assertThatThrownBy(() -> reader.studentCounts(List.of(STUDENT))).as(body).isInstanceOf(LearningFacts.Unavailable.class);
        }
        answer(owner, 200, "{\"results\":[{\"sectionId\":\"" + SECTION + "\",\"assignments\":[{\"id\":\"" + L1 + "\",\"title\":\"x\",\"dueAt\":\"soon\"}]}]}");
        assertThatThrownBy(() -> reader.publishedAssignments(List.of(SECTION))).as("a due date that is not a time").isInstanceOf(LearningFacts.Unavailable.class);
        answer(owner, 200, "{\"results\":[{\"sectionId\":\"" + SECTION + "\",\"lessons\":{}}]}");
        assertThatThrownBy(() -> reader.lessonsOfSections(List.of(SECTION))).as("lessons that are not a list").isInstanceOf(LearningFacts.Unavailable.class);
        answer(owner, 200, "{\"results\":[{\"studentId\":\"" + STUDENT + "\",\"lessonIds\":[\"nope\"]}]}");
        assertThatThrownBy(() -> reader.completedLessons(List.of(STUDENT))).as("a lesson id that is not an id").isInstanceOf(LearningFacts.Unavailable.class);
    }

    @Test
    void anOwnerThatDoesNotAnswerInTimeIsUnavailable() {
        owner.dropWithoutAnswer = true;

        assertThatThrownBy(() -> remote(200).studentCounts(List.of(STUDENT))).isInstanceOf(LearningFacts.Unavailable.class);
    }

    @Test
    void withoutAnyIdsNobodyIsAsked() {
        assertThat(remote(800).studentCounts(List.of())).isEmpty();
        assertThat(remote(800).completedLessons(List.of())).isEmpty();
        assertThat(owner.seen).isEmpty();
    }

    @Test
    void insideTheLearningModuleTheFactsAreTheModulesOwnData() {
        var submissions = Mockito.mock(AssignmentSubmissionService.class);
        var progress = Mockito.mock(LearningProgressService.class);
        var assignments = Mockito.mock(AssignmentService.class);
        var modules = Mockito.mock(CourseModuleService.class);
        var lessons = Mockito.mock(LessonService.class);
        var late = Mockito.mock(AssignmentSubmission.class);
        Mockito.when(late.status()).thenReturn(AssignmentSubmissionStatus.LATE);
        var onTime = Mockito.mock(AssignmentSubmission.class);
        Mockito.when(onTime.status()).thenReturn(AssignmentSubmissionStatus.SUBMITTED);
        Mockito.when(submissions.listForStudent(STUDENT)).thenReturn(List.of(late, onTime, onTime));
        var done = Mockito.mock(LearningProgress.class);
        Mockito.when(done.state()).thenReturn(LearningProgressState.COMPLETED);
        Mockito.when(done.lessonId()).thenReturn(L1);
        var started = Mockito.mock(LearningProgress.class);
        Mockito.when(started.state()).thenReturn(LearningProgressState.IN_PROGRESS);
        Mockito.when(progress.listForStudent(STUDENT)).thenReturn(List.of(done, started));
        var assignment = Mockito.mock(Assignment.class);
        Mockito.when(assignment.id()).thenReturn(L2);
        Mockito.when(assignment.title()).thenReturn("Lab");
        Mockito.when(assignment.dueAt()).thenReturn(Instant.parse("2026-12-01T10:00:00Z"));
        Mockito.when(assignments.listPublishedForSection(SECTION)).thenReturn(List.of(assignment));
        var module = Mockito.mock(CourseModule.class);
        Mockito.when(module.id()).thenReturn(MODULE);
        Mockito.when(modules.listActiveForSection(SECTION)).thenReturn(List.of(module));
        var lesson = Mockito.mock(Lesson.class);
        Mockito.when(lesson.id()).thenReturn(L1);
        Mockito.when(lesson.title()).thenReturn("Intro");
        Mockito.when(lesson.type()).thenReturn(LessonType.values()[0]);
        Mockito.when(lessons.listByModuleId(MODULE)).thenReturn(List.of(lesson));
        var facts = new LocalLearningFacts(submissions, progress, assignments, modules, lessons);

        assertThat(facts.studentCounts(List.of(STUDENT, OTHER, STUDENT))).containsOnlyKeys(STUDENT, OTHER)
            .containsEntry(STUDENT, new StudentCounts(3, 1, 2, 1)).containsEntry(OTHER, StudentCounts.NONE);
        assertThat(facts.publishedAssignments(List.of(SECTION)).get(SECTION)).singleElement().satisfies(a -> assertThat(a.title()).isEqualTo("Lab"));
        assertThat(facts.lessonsOfSections(List.of(SECTION)).get(SECTION)).singleElement()
            .isEqualTo(new LearningFacts.LessonRef(L1, "Intro", LessonType.values()[0].name(), MODULE));
        assertThat(facts.completedLessons(List.of(STUDENT)).get(STUDENT)).isEqualTo(Set.of(L1));
    }
}
