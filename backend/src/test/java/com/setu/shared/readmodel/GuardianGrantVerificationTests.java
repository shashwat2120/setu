package com.setu.shared.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.authorization.GuardianDelegationChecker;
import com.setu.shared.readmodel.GuardianGrantVerifier.Verdict;
import com.setu.shared.resilience.GuardedCall;
import com.setu.testsupport.BlockingStubServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Revocation takes immediate effect", for the services that only hold a copy of the grants. The decision table of
 * {@link VerifiedGuardianDelegationChecker} first, then {@link RemoteGuardianGrantVerifier} against a real socket: a yes from the owner is a
 * yes, a no is a no, and every way of not getting an answer is a refusal, never the stale local yes.
 */
class GuardianGrantVerificationTests {

    private static final UUID GUARDIAN = UUID.randomUUID();
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();

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

    private static boolean ask(GuardianDelegationChecker checker) {
        return checker.hasActiveDelegatedAccess(GUARDIAN, STUDENT, TENANT, "VIEW_ATTENDANCE", Instant.now());
    }

    private static void answer(BlockingStubServer server, int status, String body) {
        server.response = "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
    }

    private RemoteGuardianGrantVerifier verifier(String token, long responseMs) {
        return new RemoteGuardianGrantVerifier(owner.uri().toString(), token, Duration.ofMillis(500), Duration.ofMillis(responseMs), mapper,
            new GuardedCall("academic-guardian-grants", new GuardedCall.Settings(50, 10, 20, Duration.ofSeconds(10), 20, Duration.ZERO),
                e -> true, meters));
    }

    // --- the decision table

    @Test
    void aLocalNoIsANoAndNobodyIsAsked() {
        AtomicInteger asked = new AtomicInteger();
        var checker = new VerifiedGuardianDelegationChecker((g, s, t, p, n) -> false, (g, s, t, p) -> {
            asked.incrementAndGet();
            return Verdict.ACTIVE;
        }, meters);

        assertThat(ask(checker)).isFalse();
        assertThat(asked.get()).isZero();
    }

    @Test
    void aLocalYesIsOnlyAYesWhenTheOwnerConfirmsIt() {
        GuardianDelegationChecker localYes = (g, s, t, p, n) -> true;

        assertThat(ask(new VerifiedGuardianDelegationChecker(localYes, (g, s, t, p) -> Verdict.ACTIVE, meters))).isTrue();
        assertThat(ask(new VerifiedGuardianDelegationChecker(localYes, (g, s, t, p) -> Verdict.NOT_ACTIVE, meters)))
            .as("the grant was revoked and the copy had not heard yet").isFalse();
        assertThat(ask(new VerifiedGuardianDelegationChecker(localYes, (g, s, t, p) -> Verdict.UNAVAILABLE, meters)))
            .as("no answer is not a yes").isFalse();

        assertThat(meters.counter("setu.guardian.grant_verification", "outcome", "active").count()).isEqualTo(1.0);
        assertThat(meters.counter("setu.guardian.grant_verification", "outcome", "not_active").count()).isEqualTo(1.0);
        assertThat(meters.counter("setu.guardian.grant_verification", "outcome", "unavailable").count()).isEqualTo(1.0);
    }

    // --- the remote verifier

    @Test
    void theOwnersYesAndNoComeThroughAndTheRequestCarriesTheTokenAndEveryIdentifier() {
        answer(owner, 200, "{\"active\":true}");
        var verifier = verifier("s3cret", 800);

        assertThat(verifier.verify(GUARDIAN, STUDENT, TENANT, "VIEW_ATTENDANCE")).isEqualTo(Verdict.ACTIVE);
        answer(owner, 200, "{\"active\":false}");
        assertThat(verifier.verify(GUARDIAN, STUDENT, TENANT, "VIEW_ATTENDANCE")).isEqualTo(Verdict.NOT_ACTIVE);

        var seen = owner.seen.get(0);
        assertThat(seen.requestLine()).startsWith("GET /internal/guardian-grants/verify?")
            .contains("guardianUserId=" + GUARDIAN, "studentProfileId=" + STUDENT, "tenantId=" + TENANT, "permission=VIEW_ATTENDANCE");
        assertThat(seen.header("X-Internal-Token")).isEqualTo("s3cret");
        assertThat(seen.header("Authorization")).as("no user credential is forwarded").isNull();
    }

    @Test
    void everyWayOfNotGettingAnAnswerIsUnavailable() throws Exception {
        for (int status : new int[] {401, 403, 404, 500, 503}) {
            answer(owner, status, "{}");
            assertThat(verifier("t", 800).verify(GUARDIAN, STUDENT, TENANT, "P")).as("status " + status).isEqualTo(Verdict.UNAVAILABLE);
        }
        answer(owner, 200, "not json at all");
        assertThat(verifier("t", 800).verify(GUARDIAN, STUDENT, TENANT, "P")).as("malformed").isEqualTo(Verdict.UNAVAILABLE);
        answer(owner, 200, "{\"active\":\"yes\"}");
        assertThat(verifier("t", 800).verify(GUARDIAN, STUDENT, TENANT, "P")).as("a yes that is not a boolean is not a yes").isEqualTo(Verdict.UNAVAILABLE);
        answer(owner, 200, "{}");
        assertThat(verifier("t", 800).verify(GUARDIAN, STUDENT, TENANT, "P")).as("missing field").isEqualTo(Verdict.UNAVAILABLE);

        answer(owner, 200, "{\"active\":true}");
        owner.delayMillis = 1500;
        long started = System.nanoTime();
        assertThat(verifier("t", 300).verify(GUARDIAN, STUDENT, TENANT, "P")).as("too slow").isEqualTo(Verdict.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("gave up at its own timeout, not the owner's pace").isLessThan(Duration.ofMillis(1400));

        Thread.sleep(1600);   // the single-threaded stub is still asleep serving the slow request; let it finish before the next case
        owner.delayMillis = 0;
        owner.dropWithoutAnswer = true;
        assertThat(verifier("t", 300).verify(GUARDIAN, STUDENT, TENANT, "P")).as("connection dropped").isEqualTo(Verdict.UNAVAILABLE);

        owner.close();
        assertThat(verifier("t", 300).verify(GUARDIAN, STUDENT, TENANT, "P")).as("nothing listening").isEqualTo(Verdict.UNAVAILABLE);
    }

    @Test
    void aRevocationTheCopyHasNotHeardOfYetIsRefusedWhileAnOutageIsRefusedToo() {
        GuardianDelegationChecker staleLocalYes = (g, s, t, p, n) -> true;
        var checker = new VerifiedGuardianDelegationChecker(staleLocalYes, verifier("t", 500), meters);

        answer(owner, 200, "{\"active\":true}");
        assertThat(ask(checker)).isTrue();
        answer(owner, 200, "{\"active\":false}");
        assertThat(ask(checker)).as("revoked at the owner: refused at once, though the copy still says yes").isFalse();
        answer(owner, 500, "{}");
        assertThat(ask(checker)).as("owner failing: refused, not the stale yes").isFalse();
    }
}
