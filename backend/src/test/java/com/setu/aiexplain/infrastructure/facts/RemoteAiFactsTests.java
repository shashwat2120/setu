package com.setu.aiexplain.infrastructure.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.aifacts.AiFacts;
import com.setu.shared.authorization.ServicePrincipal;
import com.setu.shared.observability.Outbound;
import com.setu.testsupport.BlockingStubServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The secure tool boundary over the network (docs/contracts/ai-service.md), against a real socket: the user's own token travels and nothing
 * else could be used in its place; a refusal by the owner is an answer; every kind of not-getting-an-answer is {@link AiFacts.Unavailable},
 * never an empty or invented result; and an unwell data service is protected from a stampede.
 */
class RemoteAiFactsTests {

    private static final String TOKEN = "header.payload.signature";
    private final ObjectMapper mapper = new ObjectMapper();
    private BlockingStubServer owner;

    @BeforeEach
    void start() throws Exception {
        owner = new BlockingStubServer();
        signedInAs(UUID.randomUUID(), TOKEN);
    }

    @AfterEach
    void stop() throws Exception {
        SecurityContextHolder.clearContext();
        owner.close();
    }

    private static void signedInAs(UUID user, String token) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new ServicePrincipal(user, "jti", null, null), token, List.of()));
    }

    private RemoteAiFacts facts(long responseMs) {
        return new RemoteAiFacts(owner.uri().toString(), 500, responseMs, 20, 10, mapper, new SimpleMeterRegistry(), Outbound.NONE);
    }

    private static String request(BlockingStubServer.Seen seen) {
        return seen.requestLine();
    }

    @Test
    void everyFactIsOneGetOnItsOwnPathCarryingTheUsersOwnTokenAndNothingElse() {
        owner.response = BlockingStubServer.json(200, "{\"percentage\":91.5}");
        RemoteAiFacts facts = facts(2000);
        UUID student = UUID.randomUUID();

        facts.studentAttendance(student.toString());
        facts.academicProgress(null);
        facts.assessmentResults(student.toString());
        facts.upcomingDeadlines(null);
        facts.skillGap("job-1", student.toString());
        facts.policySearch("attendance policy");
        facts.learningResources(null);
        facts.transcript(null);
        facts.feeStatus(student.toString());

        assertThat(owner.seen).extracting(BlockingStubServer.Seen::requestLine).satisfiesExactly(
            l -> assertThat(l).startsWith("GET /ai-facts/attendance?studentId=" + student),
            l -> assertThat(l).startsWith("GET /ai-facts/academic-progress ").as("no studentId at all means the caller themselves").doesNotContain("studentId"),
            l -> assertThat(l).startsWith("GET /ai-facts/assessment-results?studentId=" + student),
            l -> assertThat(l).startsWith("GET /ai-facts/deadlines ").doesNotContain("studentId"),
            l -> assertThat(l).startsWith("GET /ai-facts/skill-gap?").contains("jobId=job-1").contains("studentId=" + student),
            l -> assertThat(l).startsWith("GET /ai-facts/policy-search?query=attendance"),
            l -> assertThat(l).startsWith("GET /ai-facts/learning-resources "),
            l -> assertThat(l).startsWith("GET /ai-facts/transcript "),
            l -> assertThat(l).startsWith("GET /ai-facts/fee-status?studentId=" + student));
        assertThat(owner.seen).allSatisfy(seen -> {
            assertThat(seen.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(seen.header("X-Internal-Token")).as("no service credential exists that could read as anyone").isNull();
        });
    }

    @Test
    void theOwnersAnswerComesBackAsItWasGivenIncludingARefusal() {
        owner.response = BlockingStubServer.json(200, "{\"reason\":\"not authorized to view this student's attendance\"}");

        Object answer = facts(2000).studentAttendance(UUID.randomUUID().toString());

        assertThat(answer).isInstanceOfSatisfying(JsonNode.class, json -> assertThat(json.get("reason").asText()).startsWith("not authorized"));
    }

    @Test
    void theCallerIsWhoTheOwnerSays() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        owner.response = BlockingStubServer.json(200, "{\"userId\":\"" + user + "\",\"tenantId\":\"" + tenant + "\"}");
        assertThat(facts(2000).caller()).isEqualTo(new AiFacts.Caller(user, tenant));

        owner.response = BlockingStubServer.json(200, "{\"userId\":\"" + user + "\",\"tenantId\":null}");
        assertThat(facts(2000).caller()).as("a platform account has no college").isEqualTo(new AiFacts.Caller(user, null));

        owner.response = BlockingStubServer.json(200, "{\"userId\":\"nobody\"}");
        assertThatThrownBy(() -> facts(2000).caller()).as("an unreadable identity is not guessed at").isInstanceOf(AiFacts.Unavailable.class);
    }

    @Test
    void withoutACallerTokenNothingIsAskedAtAll() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> facts(2000).studentAttendance(null)).isInstanceOf(AiFacts.Unavailable.class).hasMessageContaining("no caller token");
        signedInAs(UUID.randomUUID(), " ");
        assertThatThrownBy(() -> facts(2000).studentAttendance(null)).isInstanceOf(AiFacts.Unavailable.class);
        assertThat(owner.seen).as("no request was made").isEmpty();
    }

    @Test
    void everyWayOfNotGettingAnAnswerIsUnavailableNeverEmpty() throws Exception {
        for (int status : new int[] {400, 401, 403, 404, 500, 502, 503}) {
            owner.response = BlockingStubServer.json(status, "{}");
            assertThatThrownBy(() -> facts(2000).transcript(null)).as("status " + status).isInstanceOf(AiFacts.Unavailable.class);
        }
        owner.response = BlockingStubServer.json(200, "this is not json");
        assertThatThrownBy(() -> facts(2000).transcript(null)).as("not JSON").isInstanceOf(AiFacts.Unavailable.class);

        owner.response = BlockingStubServer.json(200, "{\"x\":1}");
        owner.delayMillis = 1500;
        long started = System.nanoTime();
        assertThatThrownBy(() -> facts(300).transcript(null)).as("too slow").isInstanceOf(AiFacts.Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1400));
        Thread.sleep(1600);   // the single-threaded stub is still asleep serving the slow request
        owner.delayMillis = 0;

        owner.close();
        assertThatThrownBy(() -> facts(300).transcript(null)).as("nothing listening").isInstanceOf(AiFacts.Unavailable.class);
    }

    @Test
    void aSickDataServiceIsLeftAloneButAnExpiredTokenDoesNotMakeItLookSick() {
        RemoteAiFacts facts = facts(2000);

        // 401s are the caller's problem: far more of them than the breaker's window allows for failures never open the circuit
        owner.response = BlockingStubServer.json(401, "{}");
        for (int i = 0; i < 40; i++) {
            assertThatThrownBy(() -> facts.transcript(null)).isInstanceOf(AiFacts.Unavailable.class);
        }
        owner.response = BlockingStubServer.json(200, "{\"ok\":true}");
        assertThat(facts.transcript(null)).as("still asked, still answered").isNotNull();

        // 500s are the owner's: after enough of them it is not asked again until it has had time to recover
        owner.response = BlockingStubServer.json(500, "{}");
        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> facts.transcript(null)).isInstanceOf(AiFacts.Unavailable.class);
        }
        int askedSoFar = owner.seen.size();
        owner.response = BlockingStubServer.json(200, "{\"ok\":true}");
        assertThatThrownBy(() -> facts.transcript(null)).hasMessageContaining("circuit_open");
        assertThat(owner.seen).as("an open circuit does not call the owner").hasSize(askedSoFar);
    }

    @Test
    void anAuditRecordIsAPostWithTheActionTheCorrelationIdAndDetailsButNoIdentity() throws Exception {
        owner.response = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n";
        UUID correlation = UUID.randomUUID();

        facts(2000).audit("AI_CHAT_COMPLETED", correlation, Map.of("model", "m", "toolsInvoked", List.of("getTranscript")));

        var seen = owner.seen.get(0);
        assertThat(seen.requestLine()).startsWith("POST /ai-facts/audit ");
        assertThat(seen.header("Authorization")).isEqualTo("Bearer " + TOKEN);
        JsonNode body = mapper.readTree(seen.body());
        assertThat(body.get("action").asText()).isEqualTo("AI_CHAT_COMPLETED");
        assertThat(body.get("correlationId").asText()).isEqualTo(correlation.toString());
        assertThat(body.has("userId") || body.has("tenantId")).as("who it was for is the owner's to say, from the token").isFalse();

        owner.response = BlockingStubServer.json(500, "{}");
        assertThatThrownBy(() -> facts(2000).audit("AI_CHAT_COMPLETED", correlation, Map.of())).isInstanceOf(AiFacts.Unavailable.class);
    }
}
