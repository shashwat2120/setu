package com.setu.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A one-connection-at-a-time HTTP/1.1 server on a plain blocking {@link ServerSocket}, for tests that need a real socket. Plain blocking
 * because the JDK's NIO-based servers (and Tomcat) do not start on the Windows development machine. It records what it is sent and answers
 * with whatever the test sets; it always says {@code Connection: close} because it closes after each answer.
 */
public class BlockingStubServer implements AutoCloseable {

    public record Seen(String requestLine, List<String> headers, String body) {
        public String header(String name) {
            return headers.stream()
                .filter(h -> h.toLowerCase().startsWith(name.toLowerCase() + ":"))
                .map(h -> h.substring(h.indexOf(':') + 1).trim())
                .findFirst().orElse(null);
        }
    }

    public final ServerSocket server;
    public final List<Seen> seen = new CopyOnWriteArrayList<>();
    public volatile String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok";
    public volatile long delayMillis;
    public volatile boolean dropWithoutAnswer;

    public BlockingStubServer() throws IOException {
        server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "blocking-stub-server");
        thread.setDaemon(true);
        thread.start();
    }

    public URI uri() {
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
