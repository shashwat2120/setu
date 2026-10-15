package com.setu.gateway;

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

    /** A one-connection-at-a-time HTTP/1.1 stub. */
    static class Stub implements AutoCloseable {
        record Seen(String requestLine, List<String> headers, String body) {
            String header(String name) {
                return headers.stream().filter(h -> h.toLowerCase().startsWith(name.toLowerCase() + ":")).map(h -> h.substring(h.indexOf(':') + 1).trim()).findFirst().orElse(null);
            }
        }

        final ServerSocket server;
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        volatile String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok";
        volatile long delayMillis;
        volatile boolean dropWithoutAnswer;
        private final Thread thread;

        Stub() throws IOException {
            server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "upstream-stub");
            thread.setDaemon(true);
            thread.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getLocalPort());
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    InputStream in = socket.getInputStream();
                    String requestLine = readLine(in);
                    List<String> headers = new ArrayList<>();
                    String line;
                    int length = 0;
                    boolean chunked = false;
                    while (!(line = readLine(in)).isEmpty()) {
                        headers.add(line);
                        if (line.toLowerCase().startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                        if (line.toLowerCase().startsWith("transfer-encoding:") && line.toLowerCase().contains("chunked")) {
                            chunked = true;
                        }
                    }
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    if (chunked) {
                        int size;
                        while ((size = Integer.parseInt(readLine(in).trim(), 16)) > 0) {
                            body.write(in.readNBytes(size));
                            readLine(in);
                        }
                        readLine(in);
                    } else {
                        body.write(in.readNBytes(length));
                    }
                    seen.add(new Seen(requestLine, headers, body.toString(StandardCharsets.UTF_8)));
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                    if (!dropWithoutAnswer) {
                        OutputStream out = socket.getOutputStream();
                        // the stub closes the socket after each answer, so it says so; otherwise a pooled connection would be reused after the close
                        out.write(response.replaceFirst("\r\n", "\r\nConnection: close\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException | InterruptedException e) {
                    // the client gave up (timeout test) or the stub was closed
                }
            }
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    sb.append((char) c);
                }
            }
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private Stub stub;
    private ApacheUpstream upstream;

    @BeforeEach
    void setUp() throws IOException {
        stub = new Stub();
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
        Stub.Seen seen = stub.seen.get(0);
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
        Stub.Seen seen = stub.seen.get(0);
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
    void aConnectionDroppedWithoutAResponseIsAConnectFailureSoAGetMayTryAnotherInstance() {
        stub.dropWithoutAnswer = true;
        assertThatThrownBy(() -> upstream.exchange(new Upstream.Request("GET", stub.uri().resolve("/drop"), List.of(), null, 0)))
            .isInstanceOfSatisfying(Upstream.UpstreamException.class, e -> assertThat(e.kind()).isEqualTo(Upstream.UpstreamException.Kind.CONNECT));
    }
}
