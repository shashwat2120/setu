package com.setu.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.security.AccessTokenClaims;
import com.setu.shared.security.LoginRevocations;
import com.setu.shared.security.RemoteTokenAuthenticator;
import com.setu.shared.security.TokenRejectedException;
import com.setu.shared.web.CorrelationIdFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The gateway's behaviour at the edge, with fakes for everything it talks to: token verification and revocation, the rate limiter, the
 * service directory and the upstream. One test per promise in docs/contracts/gateway.md.
 */
class GatewayFilterTests {

    private static final URI A = URI.create("http://a.internal:8082");
    private static final URI B = URI.create("http://b.internal:8082");
    private static final String GOOD = "good-token";
    private static final UUID USER = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Set<UUID> revokedLogins = new HashSet<>();
    private final List<Upstream.Request> sent = new ArrayList<>();
    private final List<String> failedInstances = new ArrayList<>();
    private final List<RouteTable.Route> relayed = new ArrayList<>();
    private List<URI> instances = List.of(A);
    private boolean denyRate;
    private Upstream.Response answer = new Upstream.Response(200, List.of(new Upstream.Header("Content-Type", "application/json")),
        new ByteArrayInputStream("{\"ok\":true}".getBytes()), null);
    private final Map<URI, Upstream.UpstreamException> failures = new java.util.HashMap<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LoginRevocations revocations = new LoginRevocations() {
            public void revokeLogin(UUID familyId) {
            }

            public void revokeAccount(UUID userId) {
            }

            public boolean isRevoked(AccessTokenClaims claims) {
                return revokedLogins.contains(claims.familyId());
            }
        };
        RemoteTokenAuthenticator authenticator = new RemoteTokenAuthenticator(token -> {
            if (!GOOD.equals(token)) {
                throw new TokenRejectedException("no");
            }
            return new AccessTokenClaims(USER, "j", Instant.now(), Instant.now().plusSeconds(600), null, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }, revocations);
        GatewayRateLimiter limiter = new GatewayRateLimiter(null) {
            @Override
            public Decision check(String routeId, String bucket, int limitPerMinute) {
                return denyRate ? new Decision(false, 17) : Decision.ALLOWED;
            }
        };
        ServiceDirectory directory = new ServiceDirectory() {
            public List<URI> available(String service) {
                return instances;
            }

            public void reportFailure(String service, URI instance) {
                failedInstances.add(instance.toString());
            }
        };
        Upstream upstream = request -> {
            sent.add(request);
            Upstream.UpstreamException failure = failures.get(URI.create(request.uri().getScheme() + "://" + request.uri().getAuthority()));
            if (failure != null) {
                throw failure;
            }
            return answer;
        };
        GatewayProperties properties = new GatewayProperties(Map.of(),
            List.of(),
            new GatewayProperties.CorsProperties(List.of("http://localhost:5180")),
            new GatewayProperties.UpstreamProperties(Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(15)),
            new GatewayProperties.LimitProperties(120, 600));
        RouteTable routes = new RouteTable(List.of(
            new GatewayProperties.RouteProperties("core", "core", List.of("/**"), List.of("/swagger-ui/**"), null, false),
            new GatewayProperties.RouteProperties("identity", "identity", List.of("/auth/**", "/identity/**"), List.of("/auth/**"), null, false),
            new GatewayProperties.RouteProperties("academic", "academic", List.of("/institution/**"), List.of(), null, false),
            new GatewayProperties.RouteProperties("live", "attendance", List.of("/live/**"), List.of("/live/**"), null, true),
            new GatewayProperties.RouteProperties("live-private", "attendance", List.of("/live-private/**"), List.of(), null, true)));
        GatewayFilter filter = new GatewayFilter(routes, authenticator, limiter, directory, upstream, objectMapper, properties,
            new SimpleMeterRegistry());
        // stands in for the relay handler: whatever the gateway lets through to it is recorded here
        OncePerRequestFilter relay = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
                relayed.add((RouteTable.Route) request.getAttribute(WebSocketRelayHandler.ROUTE_ATTRIBUTE));
                response.setStatus(204);
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(new Object()).addFilters(new CorrelationIdFilter(), filter, relay).build();
    }

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + GOOD);
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    // --- authentication

    @Test
    void aProtectedPathWithoutAValidTokenIsRefusedAtTheEdgeAndNeverForwarded() throws Exception {
        for (String header : new String[] {null, "Basic abc", "Bearer forged", "Bearer "}) {
            var builder = get("/institution/colleges");
            if (header != null) {
                builder.header("Authorization", header);
            }
            MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
            assertThat(response.getStatus()).as("header %s", header).isEqualTo(401);
            assertThat(body(response).get("code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotBlank();
        }
        assertThat(sent).isEmpty();
    }

    @Test
    void aRevokedLoginIsRefusedEvenWithAGoodSignature() throws Exception {
        revokedLogins.add(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(mvc.perform(signedIn(get("/institution/colleges"))).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(sent).isEmpty();
    }

    @Test
    void publicPathsNeedNoTokenAndAreForwarded() throws Exception {
        assertThat(mvc.perform(post("/auth/login").content("{}").contentType("application/json")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).uri()).isEqualTo(URI.create("http://a.internal:8082/auth/login"));
    }

    @Test
    void aValidTokenIsForwardedUnchangedBecauseEveryServiceVerifiesForItself() throws Exception {
        mvc.perform(signedIn(get("/institution/colleges"))).andReturn();
        assertThat(sent.get(0).headers()).contains(new Upstream.Header("Authorization", "Bearer " + GOOD));
    }

    // --- routing and forwarding

    @Test
    void noRouteIsA404InTheStandardEnvelope() throws Exception {
        GatewayFilter noRoutes = new GatewayFilter(new RouteTable(List.of()), null, null, null, null, objectMapper,
            new GatewayProperties(Map.of(), List.of(), new GatewayProperties.CorsProperties(List.of()),
                new GatewayProperties.UpstreamProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new GatewayProperties.LimitProperties(1, 1)), new SimpleMeterRegistry());
        MockMvc bare = MockMvcBuilders.standaloneSetup(new Object()).addFilters(new CorrelationIdFilter(), noRoutes).build();
        MockHttpServletResponse response = bare.perform(get("/anything")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body(response).get("code").asText()).isEqualTo("NO_ROUTE");
    }

    @Test
    void internalEndpointsAreNeverRoutedEvenWithACatchAllRouteAndAValidToken() throws Exception {
        sent.clear();
        for (String path : new String[] {"/internal/guardian-grants/verify", "/internal/anything"}) {
            MockHttpServletResponse response = mvc.perform(signedIn(get(path))).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isEqualTo(404);
            assertThat(body(response).get("code").asText()).isEqualTo("NO_ROUTE");
        }
        assertThat(sent).as("nothing reached any service").isEmpty();
    }

    @Test
    void queryStringBodyAndMethodAreForwardedAndSpoofableHeadersAreReplaced() throws Exception {
        mvc.perform(signedIn(request(HttpMethod.PUT, URI.create("/institution/colleges/1?x=a%20b&y=2")))
            .header("X-Forwarded-For", "6.6.6.6").header("Forwarded", "for=6.6.6.6").header("X-Forwarded-Host", "evil.example")
            .header("Connection", "keep-alive, X-Secret").header("Host", "api.setu.example").header(CorrelationIdFilter.HEADER_NAME, "client-id-1")
            .content("{\"name\":\"X\"}").contentType("application/json")).andReturn();

        Upstream.Request forwarded = sent.get(0);
        assertThat(forwarded.method()).isEqualTo("PUT");
        assertThat(forwarded.uri().toString()).isEqualTo("http://a.internal:8082/institution/colleges/1?x=a%20b&y=2");
        assertThat(forwarded.body()).isNotNull();
        assertThat(forwarded.headers()).extracting(Upstream.Header::name).doesNotContain("Forwarded", "Connection", "Host");
        assertThat(forwarded.headers()).contains(new Upstream.Header("X-Forwarded-Host", "api.setu.example"));
        assertThat(forwarded.headers().stream().filter(h -> h.name().equalsIgnoreCase("X-Forwarded-For")).map(Upstream.Header::value).toList())
            .containsExactly("127.0.0.1");   // the real connection, not the client's claim
        assertThat(forwarded.headers()).contains(new Upstream.Header(CorrelationIdFilter.HEADER_NAME, "client-id-1"));
    }

    @Test
    void theCorrelationIdIsGeneratedWhenMissingAndSentBothWays() throws Exception {
        MockHttpServletResponse response = mvc.perform(signedIn(get("/institution/colleges"))).andReturn().getResponse();
        String id = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(id).isNotBlank();
        assertThat(sent.get(0).headers()).contains(new Upstream.Header(CorrelationIdFilter.HEADER_NAME, id));
    }

    @Test
    void theResponseIsCopiedIncludingStatusCookiesAndBodyButNotAnotherOriginsCorsHeaders() throws Exception {
        answer = new Upstream.Response(201, List.of(new Upstream.Header("Content-Type", "application/json"),
            new Upstream.Header("Set-Cookie", "setu_rt=a; Path=/auth; HttpOnly"), new Upstream.Header("Set-Cookie", "other=b"),
            new Upstream.Header("Access-Control-Allow-Origin", "*"), new Upstream.Header("Transfer-Encoding", "chunked"),
            new Upstream.Header(CorrelationIdFilter.HEADER_NAME, "from-service")),
            new ByteArrayInputStream("{\"created\":1}".getBytes()), null);
        MockHttpServletResponse response = mvc.perform(post("/auth/login").content("{}").contentType("application/json")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"created\":1}");
        assertThat(response.getHeaders("Set-Cookie")).containsExactly("setu_rt=a; Path=/auth; HttpOnly", "other=b");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getHeader("Transfer-Encoding")).isNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotEqualTo("from-service");
    }

    @Test
    void anErrorStatusFromTheServiceIsPassedThroughNotReplaced() throws Exception {
        answer = new Upstream.Response(409, List.of(new Upstream.Header("Content-Type", "application/json")),
            new ByteArrayInputStream("{\"message\":\"full\"}".getBytes()), null);
        MockHttpServletResponse response = mvc.perform(signedIn(post("/institution/colleges"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("full");
    }

    // --- rate limiting

    @Test
    void anOverLimitCallerGets429WithRetryAfterAndIsNotForwarded() throws Exception {
        denyRate = true;
        MockHttpServletResponse response = mvc.perform(signedIn(get("/institution/colleges"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("17");
        assertThat(body(response).get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(sent).isEmpty();
    }

    // --- availability and failures

    @Test
    void noInstanceIs503WithRetryAfter() throws Exception {
        instances = List.of();
        MockHttpServletResponse response = mvc.perform(signedIn(get("/institution/colleges"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(body(response).get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void aGetIsRetriedOnAnotherInstanceWhenTheFirstRefusesTheConnectionAndTheFirstIsReported() throws Exception {
        instances = List.of(A, B);
        failures.put(A, new Upstream.UpstreamException(Upstream.UpstreamException.Kind.CONNECT, "refused", null));
        MockHttpServletResponse response = mvc.perform(signedIn(get("/institution/colleges"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(sent).extracting(r -> r.uri().getHost()).containsExactly("a.internal", "b.internal");
        assertThat(failedInstances).containsExactly(A.toString());
    }

    @Test
    void aPostIsNeverRetriedOnAnotherInstance() throws Exception {
        instances = List.of(A, B);
        failures.put(A, new Upstream.UpstreamException(Upstream.UpstreamException.Kind.CONNECT, "refused", null));
        MockHttpServletResponse response = mvc.perform(signedIn(post("/institution/colleges").content("{}").contentType("application/json")))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(sent).hasSize(1);
    }

    @Test
    void everyInstanceUnreachableIs502AndATimeoutIs504AndATimedOutGetIsNotRetried() throws Exception {
        instances = List.of(A, B);
        failures.put(A, new Upstream.UpstreamException(Upstream.UpstreamException.Kind.CONNECT, "refused", null));
        failures.put(B, new Upstream.UpstreamException(Upstream.UpstreamException.Kind.CONNECT, "refused", null));
        MockHttpServletResponse unreachable = mvc.perform(signedIn(get("/institution/colleges"))).andReturn().getResponse();
        assertThat(unreachable.getStatus()).isEqualTo(502);
        assertThat(body(unreachable).get("code").asText()).isEqualTo("BAD_GATEWAY");

        sent.clear();
        failures.clear();
        failures.put(A, new Upstream.UpstreamException(Upstream.UpstreamException.Kind.TIMEOUT, "slow", null));
        MockHttpServletResponse slow = mvc.perform(signedIn(get("/institution/colleges"))).andReturn().getResponse();
        assertThat(slow.getStatus()).isEqualTo(504);
        assertThat(body(slow).get("code").asText()).isEqualTo("GATEWAY_TIMEOUT");
        assertThat(sent).as("a request that may have been processed is not sent again").hasSize(1);
    }

    @Test
    void errorBodiesNeverLeakInstanceAddresses() throws Exception {
        failures.put(A, new Upstream.UpstreamException(Upstream.UpstreamException.Kind.CONNECT, "refused connecting to http://a.internal:8082", null));
        String text = mvc.perform(signedIn(get("/institution/colleges"))).andReturn().getResponse().getContentAsString();
        assertThat(text).doesNotContain("a.internal").doesNotContain(":8082");   // ":" so a timestamp that happens to contain those digits cannot trip it
    }

    // --- CORS and WebSocket

    @Test
    void aPreflightFromAnAllowedOriginIsAnsweredAtTheGatewayAndNotForwarded() throws Exception {
        MockHttpServletResponse response = mvc.perform(options("/institution/colleges").header("Origin", "http://localhost:5180")
            .header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5180");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).contains("Authorization");
        assertThat(sent).isEmpty();
    }

    @Test
    void aPreflightFromAnUnknownOriginIsRefused() throws Exception {
        MockHttpServletResponse response = mvc.perform(options("/institution/colleges").header("Origin", "https://evil.example")
            .header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void corsHeadersAppearOnlyForAllowedOrigins() throws Exception {
        MockHttpServletResponse allowed = mvc.perform(signedIn(get("/institution/colleges")).header("Origin", "http://localhost:5180")).andReturn().getResponse();
        assertThat(allowed.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5180");
        MockHttpServletResponse unknown = mvc.perform(signedIn(get("/institution/colleges")).header("Origin", "https://evil.example")).andReturn().getResponse();
        assertThat(unknown.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void aWebSocketUpgradeOnARouteThatDoesNotCarryLiveConnectionsIsRefusedHonestlyInsteadOfSilentlyBroken() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/ws").header("Upgrade", "websocket")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(501);
        assertThat(body(response).get("code").asText()).isEqualTo("WEBSOCKET_NOT_SUPPORTED");
        assertThat(relayed).isEmpty();
        assertThat(sent).isEmpty();
    }

    @Test
    void aWebSocketUpgradeOnALiveRouteIsHandedToTheRelayWithTheRouteThatPassedTheChecksAndNeverProxiedAsHttp() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/live/attendance").header("Upgrade", "websocket")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(relayed).singleElement().satisfies(route -> {
            assertThat(route.id()).isEqualTo("live");
            assertThat(route.service()).isEqualTo("attendance");
        });
        assertThat(sent).as("the upgrade is not an HTTP exchange with the service").isEmpty();
    }

    @Test
    void aLiveRouteThatIsNotPublicStillDemandsAValidTokenBeforeTheUpgrade() throws Exception {
        assertThat(mvc.perform(get("/live-private/x").header("Upgrade", "websocket")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/live-private/x").header("Upgrade", "websocket").header("Authorization", "Bearer forged")).andReturn()
            .getResponse().getStatus()).isEqualTo(401);
        assertThat(relayed).isEmpty();

        assertThat(mvc.perform(signedIn(get("/live-private/x")).header("Upgrade", "websocket")).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(relayed).hasSize(1);
    }

    @Test
    void aWebSocketUpgradeIsRateLimitedLikeAnyOtherRequest() throws Exception {
        denyRate = true;
        MockHttpServletResponse response = mvc.perform(get("/live/attendance").header("Upgrade", "websocket")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("17");
        assertThat(relayed).isEmpty();
    }

    @Test
    void aPlainRequestToALiveRouteIsStillAnOrdinaryProxiedRequest() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/live/info")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(sent).hasSize(1);
        assertThat(relayed).isEmpty();
    }
}
