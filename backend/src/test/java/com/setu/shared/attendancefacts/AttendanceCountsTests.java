package com.setu.shared.attendancefacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.attendance.application.AttendanceRecordService;
import com.setu.attendance.application.LocalAttendanceCounts;
import com.setu.shared.attendancefacts.AttendanceCounts.Counts;
import com.setu.shared.observability.Outbound;
import com.setu.shared.resilience.GuardedCall;
import com.setu.testsupport.BlockingStubServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The attendance counts the risk engine, the AI advisor and Career ask for come from the Attendance service once it has a database of its own. These tests hold what the
 * remote reader promises: the owner's numbers come through, the request is authenticated and asks for exactly what was meant, and anything that is not a complete, plausible
 * answer is {@link AttendanceCounts.Unavailable}, never zeros or a guess standing in for it. No database, no Spring context.
 */
class AttendanceCountsTests {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

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

    private RemoteAttendanceCounts remote(String token, long responseMs) {
        return new RemoteAttendanceCounts(owner.uri().toString(), token, Duration.ofMillis(500), Duration.ofMillis(responseMs), mapper,
            new GuardedCall("attendance-counts", new GuardedCall.Settings(50, 10, 20, Duration.ofSeconds(10), 20, Duration.ZERO), e -> true, meters), Outbound.NONE);
    }

    private static String row(UUID id, long presentOrLate, long total) {
        return "{\"enrollmentId\":\"" + id + "\",\"presentOrLate\":" + presentOrLate + ",\"total\":" + total + "}";
    }

    @Test
    void theOwnersNumbersComeThroughAndTheRequestCarriesTheTokenAndEveryEnrollmentAskedAbout() {
        answer(owner, 200, "{\"counts\":[" + row(A, 7, 9) + "," + row(B, 0, 0) + "]}");

        Map<UUID, Counts> counts = remote("s3cret", 800).forEnrollments(List.of(A, B));

        assertThat(counts).containsEntry(A, new Counts(7, 9)).containsEntry(B, new Counts(0, 0));
        var seen = owner.seen.get(0);
        assertThat(seen.requestLine()).startsWith("GET /internal/attendance/counts?").contains("enrollmentIds=" + A).contains("enrollmentIds=" + B);
        assertThat(seen.header("X-Internal-Token")).isEqualTo("s3cret");
    }

    @Test
    void aLongListIsAskedInSeveralRequestsAndPutBackTogether() {
        List<UUID> ids = IntStream.range(0, RemoteAttendanceCounts.PER_REQUEST * 2 + 1).mapToObj(i -> UUID.randomUUID()).toList();
        owner.responder = seen -> {
            StringBuilder rows = new StringBuilder();
            for (UUID id : ids) {
                if (seen.requestLine().contains(id.toString())) {
                    rows.append(rows.isEmpty() ? "" : ",").append(row(id, 1, 2));
                }
            }
            String body = "{\"counts\":[" + rows + "]}";
            return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        };

        Map<UUID, Counts> counts = remote("t", 800).forEnrollments(ids);

        assertThat(owner.seen).hasSize(3);
        assertThat(counts).hasSize(ids.size()).containsEntry(ids.get(0), new Counts(1, 2));
    }

    @Test
    void anEnrollmentTheCallerAlreadyAskedAboutIsAskedOnlyOnce() {
        answer(owner, 200, "{\"counts\":[" + row(A, 1, 1) + "]}");

        assertThat(remote("t", 800).forEnrollments(List.of(A, A, A))).containsOnlyKeys(A);
        assertThat(owner.seen.get(0).requestLine().split("enrollmentIds=", -1)).as("one occurrence of the parameter").hasSize(2);
    }

    @Test
    void anythingButAnOkAnswerIsUnavailableNotZeros() {
        var reader = remote("t", 800);
        for (int status : new int[] {401, 403, 404, 500, 503}) {
            answer(owner, status, "{}");
            assertThatThrownBy(() -> reader.forEnrollments(List.of(A))).as("status " + status).isInstanceOf(AttendanceCounts.Unavailable.class);
        }
    }

    @Test
    void anAnswerThatIsNotCompleteAndPlausibleIsUnavailable() {
        var reader = remote("t", 800);
        List<String> unusable = List.of(
            "not json",
            "{}",
            "{\"counts\":{}}",
            "{\"counts\":[]}",                                                       // asked about A, told nothing
            "{\"counts\":[" + row(B, 1, 1) + "]}",                                   // told about someone else
            "{\"counts\":[" + row(A, 1, 1) + "," + row(A, 1, 1) + "]}",              // the same one twice
            "{\"counts\":[" + row(A, 5, 3) + "]}",                                   // more present than recorded
            "{\"counts\":[" + row(A, -1, 3) + "]}",
            "{\"counts\":[{\"enrollmentId\":\"" + A + "\",\"presentOrLate\":\"1\",\"total\":2}]}",
            "{\"counts\":[{\"enrollmentId\":\"nope\",\"presentOrLate\":1,\"total\":2}]}");
        for (String body : unusable) {
            answer(owner, 200, body);
            assertThatThrownBy(() -> reader.forEnrollments(List.of(A))).as(body).isInstanceOf(AttendanceCounts.Unavailable.class);
        }
    }

    @Test
    void anOwnerThatDoesNotAnswerInTimeIsUnavailable() {
        owner.dropWithoutAnswer = true;

        assertThatThrownBy(() -> remote("t", 200).forEnrollments(List.of(A))).isInstanceOf(AttendanceCounts.Unavailable.class);
    }

    @Test
    void withoutAnyEnrollmentsNobodyIsAsked() {
        assertThat(remote("t", 800).forEnrollments(List.of())).isEmpty();
        assertThat(owner.seen).isEmpty();
    }

    @Test
    void insideTheAttendanceModuleTheCountsAreTheRecordsOwnCounts() {
        var records = Mockito.mock(AttendanceRecordService.class);
        Mockito.when(records.percentageForEnrollmentUnchecked(A)).thenReturn(new AttendanceRecordService.AttendancePercentage(4, 5));
        Mockito.when(records.percentageForEnrollmentUnchecked(B)).thenReturn(new AttendanceRecordService.AttendancePercentage(0, 0));

        var counts = new LocalAttendanceCounts(records).forEnrollments(List.of(A, B));

        assertThat(counts).containsEntry(A, new Counts(4, 5)).containsEntry(B, new Counts(0, 0));
    }
}
