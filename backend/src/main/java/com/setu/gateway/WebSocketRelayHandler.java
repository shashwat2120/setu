package com.setu.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.web.CorrelationIdFilter;
import com.setu.shared.web.ErrorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Carries a live connection (a WebSocket, in practice STOMP) from a client through the gateway to the service that owns the route, and copies
 * every frame both ways. It does not read them: the service authenticates the STOMP CONNECT frame and authorizes every subscription itself,
 * exactly as it does for a client that connects to it directly, so the gateway is never a place where live access is decided.
 * <p>
 * What the gateway does add, before any frame moves ({@link GatewayFilter}): the route must say it carries live connections, the origin must be
 * one the gateway allows, the caller is rate limited, and a token is demanded unless the route marks the handshake public (the handshake of the
 * live attendance board carries no token; the CONNECT frame does).
 * <p>
 * The connection to the service is opened <em>during</em> the handshake, so a service that cannot be reached is an honest HTTP 503 or 502 to the
 * client (with the error envelope and the correlation id) rather than a socket that opens and is then dropped. Instances are tried in the order
 * {@link ServiceDirectory} gives, and one that refuses a connection is reported so it is left out for a while. Once connected, either side
 * closing, or failing, closes the other, with the same close code where one can be sent.
 */
public class WebSocketRelayHandler extends AbstractWebSocketHandler implements SubProtocolCapable {

    /** Request attribute the gateway filter sets to the route that passed its checks; without it the handshake is refused. */
    static final String ROUTE_ATTRIBUTE = "setu.gateway.route";
    private static final String LINK_ATTRIBUTE = "setu.gateway.websocket.link";
    /** What the live services speak; both ends pick the first one the client offered that is in this list, so they pick the same one. */
    private static final List<String> SUB_PROTOCOLS = List.of("v12.stomp", "v11.stomp", "v10.stomp");
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private static final Logger log = LoggerFactory.getLogger(WebSocketRelayHandler.class);

    private final ServiceDirectory directory;
    private final WebSocketClient client;
    private final ObjectMapper objectMapper;
    private final Duration connectTimeout;
    private final MeterRegistry meters;
    private final AtomicInteger open = new AtomicInteger();

    public WebSocketRelayHandler(ServiceDirectory directory, WebSocketClient client, ObjectMapper objectMapper, Duration connectTimeout,
                                 MeterRegistry meters) {
        this.directory = directory;
        this.client = client;
        this.objectMapper = objectMapper;
        this.connectTimeout = connectTimeout;
        this.meters = meters;
        meters.gauge("setu.gateway.websocket.open", open);
    }

    @Override
    public List<String> getSubProtocols() {
        return SUB_PROTOCOLS;
    }

    /** The step that opens the connection to the owning service; register it with the handler. */
    public HandshakeInterceptor handshake() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                           Map<String, Object> attributes) throws Exception {
                return connectToService(request, response, attributes);
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
                // nothing to do on success; the link was handed to the session through the attributes
            }
        };
    }

    // --- the handshake: find the service and connect to it

    private boolean connectToService(ServerHttpRequest request, ServerHttpResponse response, Map<String, Object> attributes) throws IOException {
        RouteTable.Route route = request instanceof ServletServerHttpRequest servlet
            ? (RouteTable.Route) servlet.getServletRequest().getAttribute(ROUTE_ATTRIBUTE) : null;
        if (route == null) {
            // reached without passing the gateway filter's checks: never relay on a request nobody vetted
            meters.counter("setu.gateway.websocket", "outcome", "unvetted").increment();
            refuse(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "this connection was not accepted");
            return false;
        }
        List<URI> candidates = directory.available(route.service());
        if (candidates.isEmpty()) {
            meters.counter("setu.gateway.websocket", "outcome", "unavailable").increment();
            response.getHeaders().set("Retry-After", "5");
            refuse(request, response, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "the " + route.service() + " service has no reachable instance");
            return false;
        }

        Exception last = null;
        for (URI instance : candidates) {
            Link link = new Link();
            try {
                WebSocketSession backend = connect(instance, request, link);
                // the handler has normally attached it already; this is for a client that completes the future first
                link.backendConnected(new ConcurrentWebSocketSessionDecorator(backend, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES));
                attributes.put(LINK_ATTRIBUTE, link);
                meters.counter("setu.gateway.websocket", "outcome", "connected").increment();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                last = e;
                break;
            } catch (Exception e) {
                last = e;
                if (isUnreachable(e)) {
                    directory.reportFailure(route.service(), instance);
                    continue;
                }
                break;   // the service answered and said no (or the connection broke after it answered): trying another instance would not change that
            }
        }
        boolean timedOut = last instanceof TimeoutException || last != null && last.getCause() instanceof SocketTimeoutException;
        log.warn("gateway: WebSocket {} -> {} failed: {}", request.getURI().getRawPath(), route.service(), last == null ? "no attempt" : last.toString());
        meters.counter("setu.gateway.websocket", "outcome", timedOut ? "gateway_timeout" : "bad_gateway").increment();
        if (timedOut) {
            refuse(request, response, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT", "the " + route.service() + " service did not answer in time");
        } else {
            refuse(request, response, HttpStatus.BAD_GATEWAY, "BAD_GATEWAY", "the " + route.service() + " service could not be reached");
        }
        return false;
    }

    private WebSocketSession connect(URI instance, ServerHttpRequest request, Link link) throws Exception {
        String base = instance.toString();
        String scheme = "https".equalsIgnoreCase(instance.getScheme()) ? "wss" : "ws";
        String authority = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base).replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        URI target = URI.create(scheme + "://" + authority + request.getURI().getRawPath()
            + (request.getURI().getRawQuery() == null ? "" : "?" + request.getURI().getRawQuery()));

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        String origin = request.getHeaders().getOrigin();
        if (origin != null) {
            headers.setOrigin(origin);
        }
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization != null) {
            headers.set("Authorization", authorization);
        }
        // what a client could use to pose as the edge is dropped; these describe the real connection
        headers.set("X-Forwarded-For", request instanceof ServletServerHttpRequest servlet ? servlet.getServletRequest().getRemoteAddr() : "unknown");
        String host = request.getHeaders().getFirst("Host");
        if (host != null) {
            headers.set("X-Forwarded-Host", host);
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        headers.set(CorrelationIdFilter.HEADER_NAME, correlationId != null ? correlationId : UUID.randomUUID().toString());
        List<String> offered = new WebSocketHttpHeaders(request.getHeaders()).getSecWebSocketProtocol();
        if (!offered.isEmpty()) {
            headers.setSecWebSocketProtocol(offered);
        }

        CompletableFuture<WebSocketSession> connecting = client.execute(new BackendHandler(link), headers, target);
        try {
            return connecting.get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            connecting.cancel(true);
            throw e;
        }
    }

    private static boolean isUnreachable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof SocketTimeoutException
                || t instanceof TimeoutException) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return false;
    }

    private void refuse(ServerHttpRequest request, ServerHttpResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        objectMapper.writeValue(response.getBody(), ErrorResponse.of(status.value(), code, message, request.getURI().getRawPath(),
            correlationId != null ? correlationId : UUID.randomUUID().toString()));
    }

    // --- the client side of the relay

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Link link = (Link) session.getAttributes().get(LINK_ATTRIBUTE);
        if (link == null) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        open.incrementAndGet();
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES);
        String backendProtocol = link.backendProtocol();
        if (!java.util.Objects.equals(backendProtocol, session.getAcceptedProtocol())) {
            // the two ends settled on different protocols, so frames would not mean the same to both: fail visibly
            log.warn("gateway: WebSocket protocols differ (client {}, service {}); closing", session.getAcceptedProtocol(), backendProtocol);
            link.close(CloseStatus.PROTOCOL_ERROR);
            closeQuietly(session, CloseStatus.PROTOCOL_ERROR);
            return;
        }
        link.clientConnected(decorated);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        link(session).fromClient(message);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        link(session).fromClient(message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("gateway: WebSocket transport error from the client: {}", exception.toString());
        link(session).close(CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        open.decrementAndGet();
        link(session).close(status);
    }

    private Link link(WebSocketSession session) {
        Link link = (Link) session.getAttributes().get(LINK_ATTRIBUTE);
        return link != null ? link : new Link();   // a session that never had one: operations on it do nothing
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException | RuntimeException e) {
            log.debug("gateway: closing a WebSocket session failed: {}", e.toString());
        }
    }

    /** Close codes that describe a state, not something a peer may send (RFC 6455 section 7.4.1), become a normal close. */
    private static CloseStatus sendable(CloseStatus status) {
        int code = status.getCode();
        if (code == 1005 || code == 1006 || code == 1015) {
            return CloseStatus.NORMAL;
        }
        String reason = status.getReason();
        return reason != null && reason.length() > 100 ? new CloseStatus(code, reason.substring(0, 100)) : status;
    }

    // --- the service side of the relay

    private final class BackendHandler extends AbstractWebSocketHandler {

        private final Link link;

        BackendHandler(Link link) {
            this.link = link;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            link.backendConnected(new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES));
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            link.fromBackend(message);
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            link.fromBackend(message);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.debug("gateway: WebSocket transport error from the service: {}", exception.toString());
            link.close(CloseStatus.SERVER_ERROR);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            link.close(status);
        }
    }

    /** The two halves of one relayed connection. Frames from the service that arrive before the client's side is attached wait for it. */
    private static final class Link {

        private final Object lock = new Object();
        private WebSocketSession backend;
        private WebSocketSession client;
        private final List<WebSocketMessage<?>> early = new ArrayList<>();
        private boolean closed;

        void backendConnected(WebSocketSession session) {
            synchronized (lock) {
                if (backend == null) {
                    backend = session;
                }
            }
        }

        String backendProtocol() {
            synchronized (lock) {
                return backend == null ? null : backend.getAcceptedProtocol();
            }
        }

        void clientConnected(WebSocketSession session) {
            List<WebSocketMessage<?>> waiting;
            synchronized (lock) {
                if (closed) {
                    closeQuietly(session, CloseStatus.GOING_AWAY);
                    return;
                }
                client = session;
                waiting = new ArrayList<>(early);
                early.clear();
            }
            waiting.forEach(this::fromBackend);
        }

        void fromClient(WebSocketMessage<?> message) {
            WebSocketSession target;
            synchronized (lock) {
                target = backend;
            }
            forward(target, message);
        }

        void fromBackend(WebSocketMessage<?> message) {
            WebSocketSession target;
            synchronized (lock) {
                if (client == null) {
                    if (!closed) {
                        early.add(message);
                    }
                    return;
                }
                target = client;
            }
            forward(target, message);
        }

        private void forward(WebSocketSession target, WebSocketMessage<?> message) {
            if (target == null || !target.isOpen()) {
                close(CloseStatus.SERVER_ERROR);
                return;
            }
            try {
                target.sendMessage(message);
            } catch (IOException | RuntimeException e) {
                log.debug("gateway: relaying a WebSocket frame failed: {}", e.toString());
                close(CloseStatus.SERVER_ERROR);
            }
        }

        /** Closes both sides, once, with the given status (a state that cannot be sent becomes a normal close). */
        void close(CloseStatus status) {
            WebSocketSession a;
            WebSocketSession b;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                a = backend;
                b = client;
                early.clear();
            }
            CloseStatus toSend = sendable(status);
            if (a != null && a.isOpen()) {
                closeQuietly(a, toSend);
            }
            if (b != null && b.isOpen()) {
                closeQuietly(b, toSend);
            }
        }
    }
}
