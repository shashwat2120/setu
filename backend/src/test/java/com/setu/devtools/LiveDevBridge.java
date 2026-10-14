package com.setu.devtools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import jakarta.servlet.http.Cookie;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Dev-only local bridge, NOT part of the deployable application: exists solely because a genuinely live server
 * (embedded Tomcat via {@code mvn spring-boot:run} or the packaged jar) cannot start on this specific Windows/JDK
 * machine — confirmed this session that {@code java.nio.channels.Selector.open()} itself fails here (self-pipe
 * creation needs AF_UNIX domain sockets, which this machine blocks), and that failure is not Tomcat-specific:
 * even the JDK's own {@code com.sun.net.httpserver.HttpServer} hits the exact same exception. A plain,
 * pre-NIO {@link ServerSocket}/{@link Socket} (no {@code Selector} anywhere) was confirmed working directly
 * against this machine, so that is exactly what this class uses.
 * <p>
 * This does NOT touch backend architecture, production configuration, or Tomcat in any way — it boots the SAME
 * real {@link SpringBootTest} context (real Spring Security filter chain, real PostgreSQL, real demo data via
 * the same {@code app.demo-data.enabled=true} flag {@code DemoDataSeederVerificationTests} uses) and forwards
 * each real raw HTTP request into it through {@link MockMvc} — the SAME, already-proven, full filter-chain +
 * DispatcherServlet round trip every HTTP test in this codebase already relies on. It only exists so the React
 * frontend (running under Vite, proxied to this bridge's port) can be developed and manually verified against
 * genuinely real backend behavior on this machine, instead of against nothing at all.
 * <p>
 * Known gap, disclosed: this is a plain HTTP/1.1 request/response bridge with no WebSocket upgrade handling, so
 * STOMP/realtime features (Phase 3K) cannot be verified live through it — exactly the kind of gap the task's own
 * instructions anticipate ("document it and test the frontend integration boundary as strongly as possible
 * without weakening backend security").
 * <p>
 * Run with: {@code mvn -q test -Dtest=LiveDevBridge#serve} (left running; stop with Ctrl+C or by killing the
 * process). Point the frontend's Vite dev server proxy at {@code http://localhost:8089}.
 */
@SpringBootTest(properties = {
    "spring.ai.model.chat=${AI_PROVIDER:google-genai}",
    "spring.ai.google.genai.chat.options.model=${GEMINI_MODEL:gemini-3.8-flash}"
})
@AutoConfigureMockMvc
// Spring Boot switches metrics export off in tests; the bridge serves /actuator/prometheus to the Prometheus container, so turn it back on.
@AutoConfigureMetrics
@TestPropertySource(properties = "app.demo-data.enabled=true")
class LiveDevBridge {

    private static final int PORT = 8089;

    @Autowired
    MockMvc mockMvc;

    @Test
    void serve() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(PORT, 200, java.net.InetAddress.getByName("127.0.0.1"))) {
            System.out.println("LIVE_DEV_BRIDGE_LISTENING_ON_" + PORT);
            while (true) {
                Socket socket = serverSocket.accept();
                // Plain platform threads, deliberately not virtual ones: HikariCP's connection-pool internals use
                // synchronized blocks that can pin a virtual thread carrier during a JDBC acquire, and this bridge
                // saw exactly that — GET requests (no DB write) returned instantly, POST /auth/login (a real DB
                // write) hung indefinitely. A dev-only bridge handling a handful of concurrent browser requests
                // has no real need for virtual threads anyway.
                Thread thread = new Thread(() -> handle(socket));
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    /**
     * Reads exactly one CRLF-terminated line directly off the raw socket stream, one byte at a time — no
     * {@link BufferedReader}/{@link InputStreamReader} anywhere in this path. Wrapping a raw socket
     * {@link InputStream} in a {@code BufferedReader} for header parsing is a classic bug here: {@code
     * readLine()} fills its own internal buffer ahead of what it returns, so on a POST it silently consumes
     * some or all of the body bytes right along with the headers — a subsequent raw {@code in.read(body, ...)}
     * then blocks forever waiting for bytes that were already read and discarded. GET requests (no body) never
     * exposed this; every POST did. Reading byte-by-byte is slower, but this bridge serves a handful of
     * concurrent dev requests, not production traffic.
     */
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(128);
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            buf.write(b);
            prev = b;
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1);
    }

    private void handle(Socket socket) {
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isBlank()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts[1];

            List<String[]> headers = new ArrayList<>();
            String line;
            int contentLength = 0;
            boolean expectContinue = false;
            List<Cookie> cookies = new ArrayList<>();
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.add(new String[]{name, value});
                if (name.equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(value);
                }
                if (name.equalsIgnoreCase("Expect") && value.equalsIgnoreCase("100-continue")) {
                    expectContinue = true;
                }
                if (name.equalsIgnoreCase("Cookie")) {
                    for (String pair : value.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            cookies.add(new Cookie(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim()));
                        }
                    }
                }
            }
            // A client that sent "Expect: 100-continue" withholds the request body until the server acks —
            // without this, reading the body below blocks forever waiting for bytes the client never sends.
            if (expectContinue) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }

            byte[] body = new byte[0];
            if (contentLength > 0) {
                body = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(body, read, contentLength - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }

            MockHttpServletRequestBuilder builder = switch (method.toUpperCase(Locale.ROOT)) {
                case "GET" -> MockMvcRequestBuilders.get(target);
                case "POST" -> MockMvcRequestBuilders.post(target);
                case "PUT" -> MockMvcRequestBuilders.put(target);
                case "PATCH" -> MockMvcRequestBuilders.patch(target);
                case "DELETE" -> MockMvcRequestBuilders.delete(target);
                case "OPTIONS" -> MockMvcRequestBuilders.options(target);
                default -> throw new IllegalArgumentException("unsupported method: " + method);
            };
            for (String[] header : headers) {
                if (header[0].equalsIgnoreCase("Content-Length") || header[0].equalsIgnoreCase("Connection")
                    || header[0].equalsIgnoreCase("Host")) {
                    continue;
                }
                builder.header(header[0], header[1]);
            }
            if (!cookies.isEmpty()) {
                builder.cookie(cookies.toArray(new Cookie[0]));
            }
            if (body.length > 0) {
                builder.content(body);
            }

            System.out.println("BRIDGE_DISPATCHING: " + method + " " + target);
            org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(builder).andReturn();
            MockHttpServletResponse response = mvcResult.getResponse();
            System.out.println("BRIDGE_DISPATCHED: " + method + " " + target + " -> " + response.getStatus());
            if (mvcResult.getRequest().isAsyncStarted()) {
                relayAsync(out, mvcResult, response);
            } else {
                writeResponse(out, response);
            }
        } catch (Exception e) {
            System.out.println("BRIDGE_HANDLE_ERROR: " + e);
            e.printStackTrace(System.out);
            try {
                out(socket, 500, ("bridge error: " + e).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
    }

    private void out(Socket socket, int status, byte[] body) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + " Error\r\nContent-Length: " + body.length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void writeResponse(OutputStream out, MockHttpServletResponse response) throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(response.getStatus()).append(' ')
            .append(reason(response.getStatus())).append("\r\n");
        Enumeration<String> headerNames = java.util.Collections.enumeration(response.getHeaderNames());
        boolean sawContentType = false;
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            for (String value : response.getHeaders(name)) {
                head.append(name).append(": ").append(value).append("\r\n");
            }
            if (name.equalsIgnoreCase("Content-Type")) {
                sawContentType = true;
            }
        }
        byte[] body = response.getContentAsByteArray();
        if (!sawContentType && response.getContentType() != null) {
            head.append("Content-Type: ").append(response.getContentType()).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /**
     * Server-sent events: MockMvc returns as soon as the handler starts the async response, and the emitter keeps
     * appending to the mock response afterwards. Send the head right away as chunked, forward each new slice of the
     * body as it appears, and finish once the async result is set (the emitter completed).
     */
    private void relayAsync(OutputStream out, org.springframework.test.web.servlet.MvcResult mvcResult,
                            MockHttpServletResponse response) throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(response.getStatus()).append(' ').append(reason(response.getStatus())).append("\r\n");
        boolean sawContentType = false;
        for (String name : response.getHeaderNames()) {
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")) {
                continue;
            }
            for (String value : response.getHeaders(name)) {
                head.append(name).append(": ").append(value).append("\r\n");
            }
            if (name.equalsIgnoreCase("Content-Type")) {
                sawContentType = true;
            }
        }
        if (!sawContentType && response.getContentType() != null) {
            head.append("Content-Type: ").append(response.getContentType()).append("\r\n");
        }
        head.append("Cache-Control: no-cache\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            try {
                mvcResult.getAsyncResult(180_000);
            } catch (Exception ignored) {
                // timed out or failed — fall through and end the stream
            } finally {
                done.set(true);
            }
        }, "bridge-async-waiter");
        waiter.setDaemon(true);
        waiter.start();

        int sent = 0;
        while (true) {
            boolean finished = done.get(); // read BEFORE the slice so the last bytes are never missed
            byte[] all = response.getContentAsByteArray();
            if (all.length > sent) {
                out.write(Integer.toHexString(all.length - sent).getBytes(StandardCharsets.ISO_8859_1));
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.write(all, sent, all.length - sent);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                sent = all.length;
            }
            if (finished) {
                break;
            }
            Thread.sleep(25);
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 423 -> "Locked";
            case 500 -> "Internal Server Error";
            default -> "";
        };
    }
}
