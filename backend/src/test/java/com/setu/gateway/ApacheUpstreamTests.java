package com.setu.gateway;

import com.setu.testsupport.BlockingStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real HTTP client against a real socket. The stub is a plain blocking {@link ServerSocket} (no selector) because the JDK's NIO-based
 * servers do not start on the Windows development machine; it records what it was sent and answers with whatever the test sets.
 */
class ApacheUpstreamTests {

    private BlockingStubServer stub;
    private ApacheUpstream upstream;

    @BeforeEach
    void setUp() throws IOException {
        stub = new BlockingStubServer();
        upstream = new ApacheUpstream(Duration.ofSeconds(1), Duration.ofMillis(600));
    }

    @AfterEach
    void tearDown() throws IOException {
        stub.close();
        upstream.destroy();
    }

    private static String read(Upstream.Response response) throws IOException {
        return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void aGetReachesTheServiceAndItsAnswerComesBack() throws Exception {
        try (Upstream.Response response = upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/institution/colleges?x=1"),
            List.of(new Upstream.Header("Authorization", "Bearer t"), new Upstream.Header("X-Correlation-Id", "c-1")), null, 0))) {
            assertThat(response.status()).isEqualTo(200);
            assertThat(read(response)).isEqualTo("ok");
            assertThat(response.headers()).anyMatch(h -> h.name().equalsIgnoreCase("Content-Type") && h.value().equals("text/plain"));
        }
        BlockingStubServer.Seen seen = stub.seen.get(0);
        assertThat(seen.requestLine()).isEqualTo("GET /institution/colleges?x=1 HTTP/1.1");
        assertThat(seen.header("Authorization")).isEqualTo("Bearer t");
        assertThat(seen.header("X-Correlation-Id")).isEqualTo("c-1");
    }

    @Test
    void aPostBodyIsSentWithItsLength() throws Exception {
        byte[] json = "{\"name\":\"Asha\"}".getBytes(StandardCharsets.UTF_8);
        try (Upstream.Response response = upstream.exchange(new Upstream.Request("POST", stub.uri().resolve("/directory/students"),
            List.of(new Upstream.Header("Content-Type", "application/json")), new ByteArrayInputStream(json), json.length))) {
            assertThat(response.status()).isEqualTo(200);
        }
        BlockingStubServer.Seen seen = stub.seen.get(0);
        assertThat(seen.requestLine()).startsWith("POST /directory/students");
        assertThat(seen.header("Content-Length")).isEqualTo("15");
        assertThat(seen.body()).isEqualTo("{\"name\":\"Asha\"}");
    }

    @Test
    void aBodyOfUnknownLengthIsSentChunked() throws Exception {
        try (Upstream.Response response = upstream.exchange(new Upstream.Request("POST", stub.uri().resolve("/x"), List.of(),
            new ByteArrayInputStream("streamed".getBytes()), -1))) {
            assertThat(response.status()).isEqualTo(200);
        }
        assertThat(stub.seen.get(0).body()).isEqualTo("streamed");
    }

    @Test
    void errorStatusesAndRedirectsAreReturnedNotFollowedOrThrown() throws Exception {
        stub.response = "HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nContent-Length: 0\r\n\r\n";
        try (Upstream.Response redirect = upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/a"), List.of(), null, 0))) {
            assertThat(redirect.status()).isEqualTo(302);
            assertThat(redirect.headers()).anyMatch(h -> h.name().equalsIgnoreCase("Location") && h.value().equals("/elsewhere"));
        }
        assertThat(stub.seen).hasSize(1);

        stub.response = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 4\r\n\r\nboom";
        try (Upstream.Response failure = upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/b"), List.of(), null, 0))) {
            assertThat(failure.status()).isEqualTo(500);
            assertThat(read(failure)).isEqualTo("boom");
        }
    }

    @Test
    void compressedBodiesPassThroughUntouched() throws Exception {
        stub.response = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 3\r\n\r\n\u0001\u0002\u0003";
        try (Upstream.Response response = upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/c"), List.of(), null, 0))) {
            assertThat(response.headers()).anyMatch(h -> h.name().equalsIgnoreCase("Content-Encoding") && h.value().equals("gzip"));
            assertThat(response.body().readAllBytes()).containsExactly(1, 2, 3);
        }
    }

    @Test
    void aClosedPortIsAConnectFailure() throws Exception {
        URI closed = stub.uri();
        stub.close();
        assertThatThrownBy(() -> upstream.exchange(new Upstream.Request("GET", closed.resolve("/"), List.of(), null, 0)))
            .isInstanceOfSatisfying(Upstream.UpstreamException.class, e -> assertThat(e.kind()).isEqualTo(Upstream.UpstreamException.Kind.CONNECT));
    }

    @Test
    void aServiceThatNeverAnswersIsATimeoutNotAConnectFailure() {
        stub.delayMillis = 3000;
        assertThatThrownBy(() -> upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/slow"), List.of(), null, 0)))
            .isInstanceOfSatisfying(Upstream.UpstreamException.class, e -> assertThat(e.kind()).isEqualTo(Upstream.UpstreamException.Kind.TIMEOUT));
    }

    @Test
    void aRouteThatIsSlowByNatureMayWaitLongerThanTheClientsDefaultForItsOwnRequestsOnly() throws Exception {
        stub.delayMillis = 1500;   // longer than the client's 600 ms default, shorter than the route's own 5 s

        try (Upstream.Response response = upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/ai/chat"), List.of(), null, 0, Duration.ofSeconds(5)))) {
            assertThat(response.status()).as("the route's own patience applied").isEqualTo(200);
        }

        assertThatThrownBy(() -> upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/other"), List.of(), null, 0)))
            .as("every other request keeps the default")
            .isInstanceOfSatisfying(Upstream.UpstreamException.class, e -> assertThat(e.kind()).isEqualTo(Upstream.UpstreamException.Kind.TIMEOUT));
    }

    @Test
    void aRouteTimeoutThatIsShorterThanTheDefaultAlsoApplies() {
        stub.delayMillis = 1500;
        assertThatThrownBy(() -> upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/fast-only"), List.of(), null, 0, Duration.ofMillis(200))))
            .isInstanceOfSatisfying(Upstream.UpstreamException.class, e -> assertThat(e.kind()).isEqualTo(Upstream.UpstreamException.Kind.TIMEOUT));
    }

    @Test
    void aConnectionDroppedWithoutAResponseIsAConnectFailureSoAGetMayTryAnotherInstance() {
        stub.dropWithoutAnswer = true;
        assertThatThrownBy(() -> upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/drop"), List.of(), null, 0)))
            .isInstanceOfSatisfying(Upstream.UpstreamException.class, e -> assertThat(e.kind()).isEqualTo(Upstream.UpstreamException.Kind.CONNECT));
    }
}
