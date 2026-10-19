package com.setu.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the gateway promises about a relayed live connection (docs/contracts/gateway.md): it connects to the owning service during the handshake
 * so an unreachable service is an honest HTTP error, carries every frame both ways unread, passes on the headers the service needs and none a
 * client could use to pose as the edge, and closes both sides together. Fake sessions and a fake connection to the service stand in for real
 * sockets (an embedded server cannot be started in this environment); the real thing is exercised by {@code infra/scripts/smoke-websocket.js}
 * against the containers.
 */
class WebSocketRelayHandlerTests {

    private static final URI A = URI.create("http://a.internal:8086");
    private static final URI B = URI.create("http://b.internal:8086");
    private static final RouteTable.Route ROUTE = new RouteTable.Route("attendance-live", "attendance", 60, true, null);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<URI> failedInstances = new ArrayList<>();
    private List<URI> instances = List.of(A);

    /** What the fake connection to the service was asked to do, and how it should answer. */
    private final List<URI> connectedTo = new ArrayList<>();
    private final List<WebSocketHttpHeaders> connectHeaders = new ArrayList<>();
    private final Map<URI, Throwable> connectFailures = new HashMap<>();
    private boolean neverAnswers;
    private WebSocketHandler backendHandler;
    private WebSocketSession backend;
    private final List<WebSocketMessage<?>> sentToBackend = new ArrayList<>();
    private String backendProtocol = "v12.stomp";

    private WebSocketRelayHandler relay;
    private HandshakeInterceptor handshake;

    @BeforeEach
    void setUp() {
        ServiceDirectory directory = new ServiceDirectory() {
            public List<URI> available(String service) {
                return instances;
            }

            public void reportFailure(String service, URI instance) {
                failedInstances.add(instance);
            }
        };
        backend = session("backend", backendProtocol, sentToBackend, new HashMap<>());
        WebSocketClient client = mock(WebSocketClient.class);
        when(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class))).thenAnswer(call -> {
            WebSocketHandler handler = call.getArgument(0);
            WebSocketHttpHeaders headers = call.getArgument(1);
            URI target = call.getArgument(2);
            connectedTo.add(target);
            connectHeaders.add(headers);
            URI instance = URI.create("http://" + target.getAuthority());
            Throwable failure = connectFailures.get(instance);
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            if (neverAnswers) {
                return new CompletableFuture<WebSocketSession>();
            }
            backendHandler = handler;
            handler.afterConnectionEstablished(backend);
            return CompletableFuture.completedFuture(backend);
        });
        relay = new WebSocketRelayHandler(directory, client, objectMapper, Duration.ofMillis(200), new SimpleMeterRegistry());
        handshake = relay.handshake();
    }

    private static WebSocketSession session(String id, String protocol, List<WebSocketMessage<?>> sent, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAcceptedProtocol()).thenReturn(protocol);
        when(session.getAttributes()).thenReturn(attributes);
        try {
            org.mockito.Mockito.doAnswer(call -> {
                sent.add(call.getArgument(0));
                return null;
            }).when(session).sendMessage(any(WebSocketMessage.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return session;
    }

    private MockHttpServletRequest upgradeRequest(boolean vetted) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws");
        request.setQueryString("room=7");
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("Host", "college.example");
        request.addHeader("Origin", "http://localhost:5180");
        request.addHeader("Sec-WebSocket-Protocol", "v12.stomp, v11.stomp");
        request.addHeader("Authorization", "Bearer abc");
        request.addHeader("X-Forwarded-For", "6.6.6.6");   // a client claiming to be somebody else
        if (vetted) {
            request.setAttribute(WebSocketRelayHandler.ROUTE_ATTRIBUTE, ROUTE);
        }
        return request;
    }

    private boolean handshake(MockHttpServletRequest request, MockHttpServletResponse response, Map<String, Object> attributes) throws Exception {
        return handshake.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response), relay, attributes);
    }

    private WebSocketSession openClient(Map<String, Object> attributes, List<WebSocketMessage<?>> received, String protocol) throws Exception {
        WebSocketSession client = session("client", protocol, received, attributes);
        relay.afterConnectionEstablished(client);
        return client;
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    // --- the handshake

    @Test
    void theHandshakeConnectsToTheOwningServiceWithTheSamePathAndQueryAndTheHeadersItNeeds() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handshake(upgradeRequest(true), response, new HashMap<>())).isTrue();

        assertThat(connectedTo).containsExactly(URI.create("ws://a.internal:8086/ws?room=7"));
        WebSocketHttpHeaders sent = connectHeaders.get(0);
        assertThat(sent.getOrigin()).isEqualTo("http://localhost:5180");
        assertThat(sent.getFirst("Authorization")).isEqualTo("Bearer abc");
        assertThat(sent.getSecWebSocketProtocol()).containsExactly("v12.stomp", "v11.stomp");
        assertThat(sent.getFirst("X-Correlation-Id")).isNotBlank();
        assertThat(sent.getFirst("X-Forwarded-Host")).isEqualTo("college.example");
    }

    @Test
    void aClientCannotPoseAsTheEdgeByClaimingAnAddress() throws Exception {
        assertThat(handshake(upgradeRequest(true), new MockHttpServletResponse(), new HashMap<>())).isTrue();
        assertThat(connectHeaders.get(0).get("X-Forwarded-For")).containsExactly("203.0.113.9");
    }

    @Test
    void aRequestThatDidNotPassTheGatewayChecksIsNeverRelayed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handshake(upgradeRequest(false), response, new HashMap<>())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(connectedTo).isEmpty();
    }

    @Test
    void noInstanceIsAnHonest503WithTheErrorEnvelopeAndNothingIsOpened() throws Exception {
        instances = List.of();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handshake(upgradeRequest(true), response, new HashMap<>())).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(body(response).get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(connectedTo).isEmpty();
    }

    @Test
    void anInstanceThatRefusesTheConnectionIsReportedAndTheNextOneIsTried() throws Exception {
        instances = List.of(A, B);
        connectFailures.put(A, new ConnectException("refused"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handshake(upgradeRequest(true), response, new HashMap<>())).isTrue();

        assertThat(failedInstances).containsExactly(A);
        assertThat(connectedTo).extracting(URI::getAuthority).containsExactly("a.internal:8086", "b.internal:8086");
    }

    @Test
    void whenEveryInstanceRefusesTheClientGetsAnHonest502() throws Exception {
        instances = List.of(A, B);
        connectFailures.put(A, new ConnectException("refused"));
        connectFailures.put(B, new ConnectException("refused"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handshake(upgradeRequest(true), response, new HashMap<>())).isFalse();
        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(body(response).get("code").asText()).isEqualTo("BAD_GATEWAY");
        assertThat(failedInstances).containsExactly(A, B);
    }

    @Test
    void aServiceThatAnswersAndRefusesTheUpgradeIsNotQuarantinedAndNoOtherInstanceIsTried() throws Exception {
        instances = List.of(A, B);
        connectFailures.put(A, new IllegalStateException("the HTTP response from the server [403] did not permit the HTTP upgrade"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handshake(upgradeRequest(true), response, new HashMap<>())).isFalse();

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(failedInstances).isEmpty();
        assertThat(connectedTo).hasSize(1);
    }

    @Test
    void aServiceThatDoesNotAnswerInTimeIsA504() throws Exception {
        neverAnswers = true;
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handshake(upgradeRequest(true), response, new HashMap<>())).isFalse();
        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(body(response).get("code").asText()).isEqualTo("GATEWAY_TIMEOUT");
    }

    // --- the relay

    @Test
    void framesGoBothWaysUnchanged() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(upgradeRequest(true), new MockHttpServletResponse(), attributes)).isTrue();
        List<WebSocketMessage<?>> receivedByClient = new ArrayList<>();
        WebSocketSession client = openClient(attributes, receivedByClient, "v12.stomp");

        TextMessage connect = new TextMessage("CONNECT\naccept-version:1.2\n\n\u0000");
        relay.handleMessage(client, connect);
        assertThat(sentToBackend).containsExactly(connect);

        TextMessage update = new TextMessage("MESSAGE\ndestination:/topic/attendance\n\n{\"present\":3}\u0000");
        backendHandler.handleMessage(backend, update);
        assertThat(receivedByClient).containsExactly(update);
    }

    @Test
    void framesTheServiceSendsBeforeTheClientIsAttachedWaitForItAndKeepTheirOrder() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(upgradeRequest(true), new MockHttpServletResponse(), attributes)).isTrue();
        TextMessage first = new TextMessage("one");
        TextMessage second = new TextMessage("two");
        backendHandler.handleMessage(backend, first);
        backendHandler.handleMessage(backend, second);

        List<WebSocketMessage<?>> receivedByClient = new ArrayList<>();
        openClient(attributes, receivedByClient, "v12.stomp");
        assertThat(receivedByClient).containsExactly(first, second);
    }

    @Test
    void theClientClosingClosesTheServiceSideWithTheSameStatus() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(upgradeRequest(true), new MockHttpServletResponse(), attributes)).isTrue();
        WebSocketSession client = openClient(attributes, new ArrayList<>(), "v12.stomp");

        relay.afterConnectionClosed(client, CloseStatus.GOING_AWAY);

        verify(backend).close(CloseStatus.GOING_AWAY);
    }

    @Test
    void theServiceClosingClosesTheClientAndAStateThatCannotBeSentBecomesANormalClose() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(upgradeRequest(true), new MockHttpServletResponse(), attributes)).isTrue();
        WebSocketSession client = openClient(attributes, new ArrayList<>(), "v12.stomp");

        backendHandler.afterConnectionClosed(backend, CloseStatus.NO_CLOSE_FRAME);   // 1006: the socket vanished, nothing a peer may send

        verify(client).close(CloseStatus.NORMAL);
    }

    @Test
    void aFailureOnEitherSideClosesBothSidesOnce() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(upgradeRequest(true), new MockHttpServletResponse(), attributes)).isTrue();
        WebSocketSession client = openClient(attributes, new ArrayList<>(), "v12.stomp");

        backendHandler.handleTransportError(backend, new java.io.IOException("reset"));
        relay.afterConnectionClosed(client, CloseStatus.NORMAL);   // the second close finds nothing left to do

        verify(client).close(CloseStatus.SERVER_ERROR);
        verify(backend).close(CloseStatus.SERVER_ERROR);
        verify(backend, org.mockito.Mockito.times(1)).close(any(CloseStatus.class));
    }

    @Test
    void twoEndsThatSettledOnDifferentProtocolsAreClosedInsteadOfCarryingFramesThatMeanDifferentThings() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(upgradeRequest(true), new MockHttpServletResponse(), attributes)).isTrue();
        List<WebSocketMessage<?>> receivedByClient = new ArrayList<>();
        WebSocketSession client = openClient(attributes, receivedByClient, "v11.stomp");

        verify(client).close(CloseStatus.PROTOCOL_ERROR);
        verify(backend).close(CloseStatus.PROTOCOL_ERROR);
        backendHandler.handleMessage(backend, new TextMessage("late"));
        assertThat(receivedByClient).isEmpty();
    }

    @Test
    void aClientSessionThatNeverPassedTheHandshakeIsClosedAndNothingIsRelayedForIt() throws Exception {
        WebSocketSession stray = openClient(new HashMap<>(), new ArrayList<>(), "v12.stomp");
        verify(stray).close(CloseStatus.SERVER_ERROR);
        relay.handleMessage(stray, new TextMessage("hello"));
        verify(backend, never()).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void theRelaySpeaksTheStompProtocolsTheLiveServicesSpeak() {
        assertThat(relay.getSubProtocols()).containsExactly("v12.stomp", "v11.stomp", "v10.stomp");
    }
}
