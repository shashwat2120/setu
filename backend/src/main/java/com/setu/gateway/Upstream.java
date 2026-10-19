package com.setu.gateway;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/** One HTTP exchange with a service behind the gateway. The seam that lets the filter be tested without sockets and the client be swapped. */
public interface Upstream {

    record Header(String name, String value) {
    }

    /**
     * @param contentLength the body length, or -1 if unknown (sent chunked); {@code body} is null when the request has none
     * @param responseTimeout how long this exchange may wait for the service to answer (and between the bytes of its answer), or null for the client's default;
     *                        a route whose service is slow by nature, like the AI advisor, sets its own
     */
    record Request(String method, URI uri, List<Header> headers, InputStream body, long contentLength, Duration responseTimeout) {
        public Request(String method, URI uri, List<Header> headers, InputStream body, long contentLength) {
            this(method, uri, headers, body, contentLength, null);
        }
    }

    /** The answer, with its body still open: close it when the body has been copied. */
    record Response(int status, List<Header> headers, InputStream body, Closeable resource) implements Closeable {
        @Override
        public void close() throws IOException {
            if (resource != null) {
                resource.close();
            }
        }
    }

    class UpstreamException extends IOException {
        public enum Kind {
            /** Nothing answered: refused, unreachable, or the connection dropped before a response. Safe to try another instance. */
            CONNECT,
            /** Connected, but no answer in time. The request may have been processed. */
            TIMEOUT,
            OTHER
        }

        private final Kind kind;

        public UpstreamException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    Response exchange(Request request) throws UpstreamException;
}
